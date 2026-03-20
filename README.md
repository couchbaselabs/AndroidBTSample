This is BT Testing App using Couchbase Lite

For using your own CBL Library, you need to first update in build.gradle the dependency path of couchbase-lite-android-ee to the path where you have .aar file
Or you can also change it to normal library rather than using path(if you have that CBL ready on maven)

this is the path where you need to update CBL
https://github.com/Aniket392/BTSample/blob/6759be9897431d7e4634b614c7f7fc00bcc82604/app/build.gradle#L41

A minimal Android application demonstrating peer-to-peer (P2P) database replication using the **Couchbase Lite 4.1+ Multipeer API**. This app allows devices to automatically discover each other Bluetooth and synchronize Couchbase documents in real-time without needing a central server or Sync Gateway.

## Installation & Setup

1. Clone this repository and open the project in Android Studio.
2. Let Gradle sync the Couchbase Lite dependencies.
3. Because this is a P2P app relying on local hardware features (Bluetooth), **emulators will not work well**. You must build and run the app on at least two physical Android devices.

## How to Test P2P Sync

1. Connect both Android devices.
2. Launch the app on both devices.
3. Upon first launch, grant the required **Bluetooth** and **Nearby Devices** permissions when prompted.
4. Tap the **"Start"** button on both devices to initialize the `MultipeerReplicator`.
5. Observe the UI:
   - The status should change to `Status: Active`.
   - Under **Connected Peers**, you should see the UUID/Address of the other device appear as they discover each other.
6. Tap **"Add Doc"** on Device A. A JSON document containing a timestamp and the device model will be saved to the local Couchbase database.
7. Within a few seconds, that document will automatically appear on the screen of Device B.
