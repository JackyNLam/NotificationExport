# Notification Exporter

An Android app that captures every notification posted on your device into a local CSV file, so you can review, filter, and export your notification history to other apps (email, cloud drives, chat apps, etc.).

Everything stays on-device. The app has no network permission and uploads nothing.

---

## Features

- **Automatic capture** — a `NotificationListenerService` records the timestamp, source package, title, and text of every notification as it is posted.
- **CSV log with Excel-friendly encoding** — the history file is written with a UTF-8 BOM (`\uFEFF`) so Excel opens non-ASCII text correctly.
- **One-tap export / share** — share the raw CSV via the Android share sheet using a `FileProvider` URI.
- **Filter before export** — build a filtered copy of the log and share only what you need:
  - Skip empty or `N/A` message bodies.
  - Exclude selected apps (multi-select, sorted by how often they appear).
  - Exclude selected message titles (multi-select, sorted by frequency).
  - Filter choices are persisted in `SharedPreferences` and restored next time; a **Reset** action clears them.
- **Listener health monitoring**
  - Status line on the main screen shows *Listening*, *Stale*, or *Disconnected*, plus the timestamp of the last captured notification.
  - If nothing has been captured for 15 minutes, the app marks the listener *stale* and prompts you to toggle notification access off and on.
  - A notification warns you when the listener disconnects.
  - On device unlock, a broadcast receiver re-checks staleness and posts an alert if access appears to have been lost. (Android may silently kill notification listeners, especially under aggressive OEM battery management.)
- **Clear log** — delete the captured history from the app UI after confirmation.

---

## Requirements

- Android 7.0 (API 24) or newer — `minSdk 24`, `targetSdk`/`compileSdk` 36.
- Notification access granted manually in **Settings → Apps → Special access → Notification access**.
- On Android 13+, notification permission is needed for the app's own status/disconnect alerts.

---

## Usage

1. Install and open the app.
2. Tap **1. Enable Notification Access**, find **Notification Exporter** in the system list, and turn it on.
3. Use the phone normally. Notifications are appended to the log as they arrive.
4. Check the status line at the bottom of the screen:
   - ✅ Listening — capturing normally.
   - ⚠️ Stale — no notification for 15+ minutes; re-toggle access if needed.
   - ❌ Disconnected — access is off; re-enable it.
5. Export when ready:
   - **2a. Export History** — share the raw CSV.
   - **2b. 🔍 Filter and Export CSV** — choose filters, then share the filtered CSV.
6. **3. 🗑️ Clear Log History** — wipe the log (asks for confirmation).

> If captures stop unexpectedly (common with aggressive battery savers), disable battery optimization for this app and re-toggle notification access.

---

## CSV format

The log is stored at the app's internal files directory:

```
<app-internal-files>/notification_history.csv
```

Columns (header is written on first launch):

| Column | Description |
| --- | --- |
| `Timestamp` | Notification post time, `yyyy-MM-dd HH:mm:ss` |
| `Package` | Source app package name, e.g. `com.whatsapp` |
| `Title` | Notification title |
| `Text` | Notification body |

Example:

```csv
Timestamp,Package,Title,Text
"2026-11-19 09:14:02","com.whatsapp","Alice","See you at 7"
```

All fields are quoted and escaped (embedded quotes doubled, newlines replaced with spaces).

The **filtered** copy is written to the app cache directory as `notification_history_filtered.csv` and reuses the same BOM + column layout.

---

## Project structure

```
app/src/main/java/com/example/notificationexport/
├── MainActivity.java                     # UI: status, enable, export, filter, clear
├── MyNotificationListenerService.java    # Captures notifications → CSV
└── UnlockReceiver.java                   # Staleness check on screen unlock

app/src/main/
├── AndroidManifest.xml                   # Service/receiver/provider registration
├── res/layout/activity_main.xml          # Main screen layout
└── res/xml/file_paths.xml                # FileProvider paths (files + cache)
```

| Component | Role |
| --- | --- |
| `MainActivity` | Buttons, listener status, CSV parsing/filtering, share intents |
| `MyNotificationListenerService` | `NotificationListenerService`; appends rows to the CSV, posts disconnect alerts |
| `UnlockReceiver` | Listens for `ACTION_USER_PRESENT`; warns if the log is stale |
| `FileProvider` (`${applicationId}.fileprovider`) | Grants read access to internal files for sharing |

---

## Building

The project uses the Gradle Kotlin DSL with the Android Gradle Plugin `8.13.2` and Kotlin `2.0.21`. Note that the app logic is written in Java; the Kotlin plugin is configured for the project's Kotlin test sources.

Create a `local.properties` pointing at your SDK (Android Studio does this automatically):

```properties
sdk.dir=/path/to/Android/Sdk
```

Build from the command line:

```bash
# Debug APK
./gradlew assembleDebug

# Unit tests
./gradlew test

# Instrumented tests (requires a connected device/emulator)
./gradlew connectedAndroidTest
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

You can also open the project folder directly in Android Studio and run it on a device.

For local release builds, export the same env vars the CI uses:

```bash
export RELEASE_KEYSTORE_PATH=/path/to/notificationexport-release.jks
export RELEASE_KEYSTORE_PASSWORD='...'
export RELEASE_KEY_ALIAS=notificationexport
export RELEASE_KEY_PASSWORD='...'
export VERSION_CODE=2
export VERSION_NAME=1.0.2
./gradlew assembleRelease
```

---

## Automated builds (GitHub Actions)

Every push to `main` (or a manual **Run workflow**) builds a **signed release APK** and publishes it:

- **Artifact** — Actions run page → *Artifacts* → `NotificationExport-apk` (expires after 90 days).
- **Stable download link** — always points at the newest build:
  `https://github.com/JackyNLam/NotificationExport/releases/latest/download/NotificationExport.apk`

### Why updates install over the old app

Android only replaces an installed app if the new APK has the **same `applicationId`** and is signed with the **same signing key**, and its **`versionCode` is greater or equal**.

- The workflow signs with one persistent keystore supplied through secrets (never the throwaway debug key).
- `versionCode` is set to `${{ github.run_number }}`, which increases with every run, so each build is installable as an update.
- `applicationId` is fixed at `com.example.notificationexport`.

If you already installed a **debug** build from Android Studio, uninstall it once — it was signed with a different key and cannot be updated by the release APK.

### One-time setup

**1. Create a release keystore** (run locally, keep the file private):

```bash
keytool -genkeypair -v \
  -keystore notificationexport-release.jks \
  -alias notificationexport \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

`keytool` asks for a store password, a key password, and your name/org details. Remember the passwords — they go into the secrets below.

**2. Base64-encode it:**

```bash
base64 -w 0 notificationexport-release.jks > keystore.b64
```

**3. Add four repository secrets** under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | contents of `keystore.b64` (one long line) |
| `KEYSTORE_PASSWORD` | the keystore (store) password |
| `KEY_ALIAS` | `notificationexport` |
| `KEY_PASSWORD` | the key password |

**4. Push the workflow** (`.github/workflows/build-apk.yml`). The first run produces the APK.

> Back up `notificationexport-release.jks` somewhere safe. If you lose it you cannot sign updates that install over the existing app — users would have to uninstall first.

---

## Permissions

| Permission | Why |
| --- | --- |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (service-level) | Required by the system to bind a notification listener |
| `POST_NOTIFICATIONS` | Disconnect / stale-access alerts on Android 13+ |

The app declares **no internet permission** and performs no external communication.

---

## Notes & limitations

- Only notifications posted **after** access is granted are captured; there is no retroactive history.
- The log is plain text in app-internal storage — it is not encrypted. Anyone with root/ADB access can read it.
- The listener is a system-managed service; several OEMs kill it aggressively. The stale-check disconnection alerts exist specifically to surface this.
- The raw log is appended indefinitely with no rotation or size cap, aside from the manual **Clear Log History** action.