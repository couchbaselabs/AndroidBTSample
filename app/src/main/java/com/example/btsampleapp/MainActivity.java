package com.example.btsampleapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.couchbase.lite.*;
import com.couchbase.lite.Collection;
import com.couchbase.lite.internal.permissions.BlePermissionRequirements;

import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String DB_NAME = "multipeer-test";
    private static final String PEER_GROUP = "com.example.multipeertest";
    private static final String IDENTITY_LABEL = "com.example.multipeertest.identity";

    private Database database;
    private Collection collection;
    private MultipeerReplicator replicator;
    private ListenerToken collectionListenerToken;
    private boolean isRunning = false;

    // UI
    private TextView tvStatus, tvPeersHeader, tvPeers, tvDocsHeader, tvDocs;
    private Button btnToggle, btnAddDoc;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CouchbaseLite.init(this);

        tvStatus      = findViewById(R.id.tvStatus);
        tvPeersHeader = findViewById(R.id.tvPeersHeader);
        tvPeers       = findViewById(R.id.tvPeers);
        tvDocsHeader  = findViewById(R.id.tvDocsHeader);
        tvDocs        = findViewById(R.id.tvDocs);
        btnToggle     = findViewById(R.id.btnToggle);
        btnAddDoc     = findViewById(R.id.btnAddDoc);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        allGranted = allGranted && granted;
                    }
                    if (allGranted) {
                        startReplicator();
                    } else {
                        updateStatus("Error: Required permissions denied");
                    }
                }
        );

        try {
            database   = new Database(DB_NAME);
            collection = database.createCollection("items");


            collectionListenerToken = collection.addChangeListener(change -> {
                runOnUiThread(this::loadDocs);
            });

        } catch (CouchbaseLiteException e) {
            updateStatus("DB Error: " + e.getMessage());
            return;
        }

        btnToggle.setOnClickListener(v -> {
            if (!isRunning) checkPermissionsAndStart();
            else stopReplicator();
        });

        btnAddDoc.setOnClickListener(v -> addTestDoc());

        loadDocs();
    }

    private void checkPermissionsAndStart() {
        Set<String> missingPermissions = new HashSet<>(BlePermissionRequirements.getMissingPermissions(this));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }


        if (!missingPermissions.isEmpty()) {
            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
        } else {
            startReplicator();
        }
    }

    private void startReplicator() {
        try {
            TLSIdentity identity = getOrCreateIdentity();


            MultipeerCertificateAuthenticator authenticator =
                    new MultipeerCertificateAuthenticator((peer, certs) -> true);

            MultipeerCollectionConfiguration colConfig =
                    new MultipeerCollectionConfiguration.Builder(collection).build();

            MultipeerReplicatorConfiguration config =
                    new MultipeerReplicatorConfiguration.Builder()
                            .setPeerGroupID(PEER_GROUP)
                            .setIdentity(identity)
                            .setTransports(Set.of(MultipeerTransport.BLUETOOTH))
                            .setAuthenticator(authenticator)
                            .setCollections(Collections.singleton(colConfig))
                            .build();

            replicator = new MultipeerReplicator(config);


            replicator.addStatusListener(status -> runOnUiThread(() -> {
                String state = status.isActive() ? "Active" : "Inactive";
                String error = status.getError() != null ? " | Error: " + status.getError().getMessage() : "";
                updateStatus("Status: " + state + error);
            }));


            replicator.addPeerDiscoveryStatusListener(event -> runOnUiThread(this::refreshPeers));

            replicator.start();
            isRunning = true;
            btnToggle.setText("Stop");

        } catch (Exception e) {
            updateStatus("Start Error: " + e.getMessage());
        }
    }

    private void stopReplicator() {
        if (replicator != null) {
            replicator.stop();
            replicator = null;
        }
        isRunning = false;
        btnToggle.setText("Start");
        updateStatus("Status: Stopped");
        tvPeersHeader.setText("Connected Peers (0):");
        tvPeers.setText("None");
    }

    private TLSIdentity getOrCreateIdentity() throws CouchbaseLiteException {
        TLSIdentity existing = TLSIdentity.getIdentity(IDENTITY_LABEL);
        if (existing != null && existing.getExpiration().after(new Date())) {
            return existing;
        }
        if (existing != null) {
            TLSIdentity.deleteIdentity(IDENTITY_LABEL);
        }

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, 2);

        Map<String, String> attrs = new HashMap<>();
        attrs.put(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME, "MultipeerTest");

        Set<KeyUsage> usages = new HashSet<>();
        usages.add(KeyUsage.CLIENT_AUTH);
        usages.add(KeyUsage.SERVER_AUTH);

        return TLSIdentity.createIdentity(usages, attrs, cal.getTime(), IDENTITY_LABEL);
    }

    private void addTestDoc() {
        try {
            MutableDocument doc = new MutableDocument();
            doc.setString("message", "Hello from " + Build.MODEL);
            doc.setString("timestamp", new Date().toString());
            collection.save(doc);
        } catch (CouchbaseLiteException e) {
            updateStatus("Save Error: " + e.getMessage());
        }
    }

    private void loadDocs() {
        try {
            Query query = database.createQuery("SELECT META().id, * FROM items");
            StringBuilder sb = new StringBuilder();
            int count = 0;
            try (ResultSet rs = query.execute()) {
                for (Result row : rs) {
                    sb.append(row.getString("id"))
                            .append(" → ")
                            .append(row.toMap().toString())
                            .append("\n\n");
                    count++;
                }
            }
            final int finalCount = count;
            final String finalText = sb.length() > 0 ? sb.toString() : "No documents yet";
            runOnUiThread(() -> {
                tvDocsHeader.setText("Documents in DB (" + finalCount + "):");
                tvDocs.setText(finalText);
            });
        } catch (CouchbaseLiteException e) {
            updateStatus("Query Error: " + e.getMessage());
        }
    }

    private void refreshPeers() {
        if (replicator == null) return;

        Set<?> neighbors = replicator.getNeighborPeers();
        tvPeersHeader.setText("Connected Peers (" + neighbors.size() + "):");

        if (neighbors.isEmpty()) {
            tvPeers.setText("None");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Object peer : neighbors) {
                sb.append("• ").append(peer.toString()).append("\n");
            }
            tvPeers.setText(sb.toString());
        }
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> tvStatus.setText(msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (collectionListenerToken != null && collection != null) {
            collectionListenerToken.remove();
        }
        stopReplicator();
        try { database.close(); } catch (CouchbaseLiteException ignored) {}
    }
}
