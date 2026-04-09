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

import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String DB_NAME = "multipeer-test";
    private static final String PEER_GROUP = "com.example.multipeertest";
    private static final String IDENTITY_LABEL = "com.example.multipeertest.identity";
    private static final String PREFS_NAME = "com.example.multipeertest.prefs";
    private static final String PREF_COMMON_NAME = "identity_common_name";

    private Database database;
    private Collection collection;
    private MultipeerReplicator replicator;
    private ListenerToken collectionListenerToken;
    private boolean isRunning = false;

    // UI
    private TextView tvStatus, tvPeersHeader, tvPeers, tvDocsHeader, tvDocs, tvIDHeader, tvID;
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
        tvIDHeader    = findViewById(R.id.tvIDHeader);
        tvID          = findViewById(R.id.tvID);

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
        replicator = createReplicator();
        if(replicator == null) {
            return;
        }

        Set<String> missingPermissions = replicator.getMissingPermissions(this);
        if (!missingPermissions.isEmpty()) {
            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
        } else {
            startReplicator();
        }
    }

    private MultipeerReplicator createReplicator()
    {
        MultipeerCertificateAuthenticator authenticator =
                new MultipeerCertificateAuthenticator((peer, certs) -> true);

        MultipeerCollectionConfiguration colConfig =
                new MultipeerCollectionConfiguration.Builder(collection).build();

        MultipeerReplicatorConfiguration.Builder configBuilder =
                new MultipeerReplicatorConfiguration.Builder()
                        .setPeerGroupID(PEER_GROUP)
                        .setTransports(Set.of(MultipeerTransport.BLUETOOTH))
                        .setAuthenticator(authenticator)
                        .setCollections(Collections.singleton(colConfig));

        try {
            TLSIdentity identity = getOrCreateIdentity();
            configBuilder.setIdentity(identity);
            MultipeerReplicator replicator = new MultipeerReplicator(configBuilder.build());
            tvID.setText(replicator.getPeerId().toString());
            return replicator;
        } catch(CouchbaseLiteException e) {
            updateStatus("Start Error: " + e.getMessage());
        }

        return null;
    }

    private void startReplicator() {
        try {
            replicator.addStatusListener(status -> runOnUiThread(() -> {
                String state = status.isActive() ? "Active" : "Inactive";
                String error = status.getError() != null ? " | Error: " + status.getError().getMessage() : "";
                updateStatus("Status: " + state + error);
            }));


            replicator.addPeerDiscoveryStatusListener(event -> {
                Log.d("MainActivity", "Peer discovery event: " + event.isOnline());
                runOnUiThread(this::refreshPeers);
            });

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

    private String getCommonName() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cn = prefs.getString(PREF_COMMON_NAME, null);
        if (cn == null) {
            String raw = UUID.randomUUID().toString().replace("-", "");
            cn = "A-" + raw.substring(0, 6).toUpperCase();
            prefs.edit().putString(PREF_COMMON_NAME, cn).apply();
        }
        return cn;
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
        attrs.put(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME, getCommonName());

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

        Set<PeerInfo.PeerId> neighbors = replicator.getNeighborPeers();
        tvPeersHeader.setText("Connected Peers (" + neighbors.size() + "):");

        if (neighbors.isEmpty()) {
            tvPeers.setText("None");
        } else {
            StringBuilder sb = new StringBuilder();
            for (PeerInfo.PeerId peer : neighbors) {
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
