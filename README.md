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
  - **Exclude** selected apps and message titles (multi-select, sorted by how often they appear).
  - **Include only** selected apps and message titles — the inverse filter. An include list wins over the exclude list when enabled and non-empty.
  - Filter choices are persisted in `SharedPreferences`, restored next time, and reused by the scheduled export; a **Reset** action clears them.
- **Scheduled auto-export** — choose a time of day and an interval of *N* days (1 = daily), and the app writes a filtered CSV to
  `Android/data/com.example.notificationexport/files/exports/` (app-specific external storage, so no storage permission is required). A persisted row cursor means a row is exported at most once, so nothing is duplicated between runs; a low-priority notification confirms the save.
- **Listener health monitoring**
  - Status line on the main screen shows *Listening*, *Stale*, or *Disconnected*, plus the timestamp of the last captured notification.
  - Every 30 minutes a background job checks the log. If nothing was captured in the last 30 minutes it posts a **silent probe notification** and waits 8 seconds: if the probe is not captured either, the listener is considered broken and a high-priority alert asks you to re-toggle notification access.
  - A notification warns you immediately when the listener disconnects.
  - On device unlock, a broadcast receiver re-checks staleness and posts an alert if access appears to have been lost. (Android may silently kill notification listeners, especially under aggressive OEM battery management.)
  - **Self-test now** in the schedule dialog runs the same probe on demand, and then writes a **debug CSV** (`debug_export_<timestamp>.csv`) containing the real captured rows with no filter applied. The debug file is isolated: it never advances the auto-export cursor, so those same rows can still appear in a future scheduled export.
- **Clear log by day** — delete only rows captured before a chosen date, or clear the whole log. Before deleting, the app counts rows that have **never been exported** and asks for explicit confirmation (`Delete anyway`), so unexported history is not lost by accident.
- **Custom launcher icon** — adaptive icon built from a bagel‑tan illustration (a phone shown with a floating notification card over it and an export arrow that rises and curves out to the upper right), a themed monochrome silhouette for Android 13+ themed icons, and generated legacy PNGs for API 24–25 with round and squircle masks.

---

## Requirements

- Android 7.0 (API 24) or newer — `minSdk 24`, `targetSdk`/`compileSdk` 36.
- Notification access granted manually in **Settings → Apps → Special access → Notification access**.
- On Android 13+, notification permission is needed for the app's own status/disconnect/capture alerts.

---

## Usage

1. Install and open the app.
2. Tap **1. Enable Notification Access**, find **Notification Exporter** in the system list, and turn it on.
3. Use the phone normally. Notifications are appended to the log as they arrive.
4. Check the status line at the bottom of the screen:
   - ✅ Listening — capturing normally.
   - ⚠️ No capture for 30 min — re-toggle access if the background check has not already alerted you.
   - ❌ Disconnected — access is off; re-enable it.
5. Export when ready:
   - **2a. Export Full CSV History** — share the raw CSV.
   - **2b. 🔍 Filter and Export CSV** — choose a day range (**From** / **To**, defaulting to the whole log), then the app and message filters, then share the filtered CSV.
6. **3. ⏰ Auto-Export Schedule** — enable, set "every N day(s)", pick a time, save. The folder path and next run are shown under the button.
7. **4. 🗑️ Clear Log History** — pick a cutoff day (default: keep today, delete older), or tick *Clear the entire log instead*. Rows that were never exported trigger an extra warning before deletion.

> If captures stop unexpectedly (common with aggressive battery savers), disable battery optimization for this app and re-toggle notification access.

### Scheduled export details

- Storage path: `/storage/emulated/0/Android/data/com.example.notificationexport/files/exports/`
- File name: `notifications_YYYY-MM-DD_HHmm.csv` (one per run, UTF-8 with BOM, same columns as the raw log).
- A stored cursor records how many raw rows have already been exported, so each run writes only never-exported rows — no duplicates even when two notifications share the same timestamp. Clearing the log resets the cursor.
- Runs once every *N* days (N is configurable, 1 = daily) using a **single inexact alarm** that is re-armed after each run: Android may delay it by a few minutes and Doze can defer it to a maintenance window, and no exact-alarm permission is requested.
- The alarm and the 30-minute health check are re-armed after a reboot or app update.

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
├── MainActivity.java                     # UI: status, enable, export, filter, schedule, clear
├── MyNotificationListenerService.java    # Captures notifications → CSV; self-test hook
├── FilterConfig.java                     # Persisted filter rules (exclude + include-only)
├── CsvFilter.java                        # Applies rules, writes UTF-8-BOM CSV
├── ScheduleManager.java                  # N-day alarm + export cursor + folder
├── AutoExportReceiver.java               # Writes the scheduled filtered export
├── HealthCheckJobService.java            # 30-minute capture health check
├── SelfTest.java                         # Silent probe notification
├── DebugExport.java                      # One-off debug CSV (cursor-safe)
├── UnlockReceiver.java                   # Staleness check on screen unlock
└── BootReceiver.java                     # Re-arms alarm + job after boot/update

app/src/main/
├── AndroidManifest.xml                   # Service/receiver/job/provider registration
├── res/layout/activity_main.xml          # Main screen layout
├── res/drawable/ic_launcher_*.xml        # Adaptive icon layers + monochrome theme layer
├── res/drawable/ic_stat_export.xml       # Status-bar notification icon
├── res/mipmap-*/                         # Legacy launcher PNGs (API 24–25)
└── res/xml/file_paths.xml                # FileProvider paths (files + cache + external)
```

| Component | Role |
| --- | --- |
| `MainActivity` | Buttons, listener status, filter dialog, schedule dialog, share intents |
| `MyNotificationListenerService` | `NotificationListenerService`; appends rows to the CSV, posts disconnect alerts, flags self-test probes |
| `FilterConfig` / `CsvFilter` | Shared rule set and the exporter that applies it (manual and scheduled) |
| `ScheduleManager` / `AutoExportReceiver` | Inexact daily alarm and the incremental filtered export |
| `HealthCheckJobService` / `SelfTest` | Periodic 30-minute check with a silent probe notification |
| `UnlockReceiver` | Listens for `ACTION_USER_PRESENT`; warns if the log is stale |
| `BootReceiver` | Re-arms the alarm and the job on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| `FileProvider` (`${applicationId}.fileprovider`) | Grants read access to internal, cache and export files for sharing |

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
| `POST_NOTIFICATIONS` | Disconnect / stale / capture-problem alerts on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | Re-arms the daily export alarm and the persisted 30-minute health-check job after a reboot |

The app declares **no internet permission** and performs no external communication.

---

## Notes & limitations

- Only notifications posted **after** access is granted are captured; there is no retroactive history.
- The log is plain text in app-internal storage — it is not encrypted. Anyone with root/ADB access can read it.
- The listener is a system-managed service; several OEMs kill it aggressively. The stale-check disconnection alerts exist specifically to surface this.
- The raw log is appended indefinitely with no rotation or size cap, aside from the manual **Clear Log History** action.