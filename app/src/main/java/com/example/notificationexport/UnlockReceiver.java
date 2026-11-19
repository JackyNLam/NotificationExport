package com.example.notificationexport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UnlockReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
            checkAndNotify(context);
        }
    }

    private void checkAndNotify(Context context) {
        String lastTimeStr = getLastNotificationTime(context);
        if (lastTimeStr != null && isStale(lastTimeStr, 30)) {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            if (nm.areNotificationsEnabled()) {
                Intent openSettings = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                openSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        context, 0, openSettings,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                );

                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "listener_status_channel")
                        .setSmallIcon(R.drawable.ic_stat_export)
                        .setContentTitle("⚠️ Notification Listener Stale")
                        .setContentText("Last capture >30min ago. Tap to re-toggle access.")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi);

                nm.notify(1001, builder.build());
            }
        }
    }

    private String getLastNotificationTime(Context context) {
        File csvFile = new File(context.getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return null;
        String lastTime = null;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(csvFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("Timestamp")) continue;
                if (line.startsWith("\"")) {
                    int endQuote = line.indexOf("\"", 1);
                    if (endQuote > 1) lastTime = line.substring(1, endQuote);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return lastTime;
    }

    private boolean isStale(String lastTimeStr, int minutes) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date lastDate = sdf.parse(lastTimeStr);
            return (System.currentTimeMillis() - lastDate.getTime()) > (minutes * 60 * 1000L);
        } catch (Exception e) { return false; }
    }
}