package com.example.notificationexport; // ⚠️ Keep your exact package

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class MyNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "NotifListener";
    private static final String CHANNEL_ID = "listener_status_channel";
    private static final int DISCONNECT_NOTIF_ID = 999;
    private File csvFile;

    // In-process capture signals used by the health check (same process as the job service).
    private static volatile long lastCaptureAt = 0L;
    private static volatile long selfTestSeenAt = 0L;

    public static long getLastCaptureAt() {
        return lastCaptureAt;
    }

    public static long getSelfTestSeenAt() {
        return selfTestSeenAt;
    }

    public static void resetSelfTest() {
        selfTestSeenAt = 0L;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        csvFile = new File(getFilesDir(), "notification_history.csv");
        createNotificationChannel();

        if (!csvFile.exists() || csvFile.length() == 0) {
            try (FileWriter w = new FileWriter(csvFile, true)) {
                w.write('\uFEFF');  // ← ADD THIS LINE: UTF-8 BOM for Excel
                w.write("Timestamp,Package,Title,Text\n");
            } catch (IOException e) {
                Log.e(TAG, "Failed to write CSV header", e);
            }
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "✅ LISTENER CONNECTED");
        cancelDisconnectNotification();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.e(TAG, "🔴 LISTENER DISCONNECTED");
        postDisconnectNotification();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Listener Status", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when notification access is lost");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void postDisconnectNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("⚠️ Notification Access Lost")
                .setContentText("Tap to re-enable Notification Exporter")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        getSystemService(NotificationManager.class).notify(DISCONNECT_NOTIF_ID, builder.build());
    }

    private void cancelDisconnectNotification() {
        getSystemService(NotificationManager.class).cancel(DISCONNECT_NOTIF_ID);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Bundle extras = sbn.getNotification().extras;
            CharSequence titleSeq = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);

            String title = (titleSeq != null) ? titleSeq.toString().trim() : "";
            String text = (textSeq != null) ? textSeq.toString().trim() : "";
            String pkg = sbn.getPackageName();

            lastCaptureAt = System.currentTimeMillis();

            // Our own notifications are probes/alerts, not user history.
            if (getPackageName().equals(pkg)) {
                if (SelfTest.TITLE.equals(title)) {
                    selfTestSeenAt = lastCaptureAt;
                }
                return;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date(sbn.getPostTime()));

            writeCsvRow(timestamp, pkg, title, text);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to parse notification", e);
        }
    }

    private void writeCsvRow(String timestamp, String pkg, String title, String text) {
        try (FileWriter w = new FileWriter(csvFile, true)) {
            w.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    escapeCsv(timestamp),
                    escapeCsv(pkg),
                    escapeCsv(title),
                    escapeCsv(text)));
        } catch (IOException e) {
            Log.e(TAG, "❌ CSV write failed", e);
        }
    }

    // Safely escape commas, quotes, and newlines for CSV
    private String escapeCsv(String field) {
        if (field == null) return "";
        String clean = field.replace("\n", " ").replace("\r", "");
        return clean.replace("\"", "\"\"");
    }
}