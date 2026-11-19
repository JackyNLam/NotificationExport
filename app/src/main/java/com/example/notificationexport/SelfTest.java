package com.example.notificationexport;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Posts a silent probe notification and reports whether the capture service saw it.
 * This is the only reliable way to tell a live listener from one the system has
 * silently unbound.
 */
public final class SelfTest {

    public static final String TITLE = "Notification Exporter self-test";
    public static final String CHANNEL_ID = "selftest_channel";
    private static final int NOTIF_ID = 3001;
    private static final long GRACE_MS = 8000L;

    public interface Callback {
        void onResult(boolean captured);
    }

    private SelfTest() {
    }

    public static void run(Context ctx, Callback callback) {
        final Context app = ctx.getApplicationContext();
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Handler handler = new Handler(Looper.getMainLooper());

        if (nm == null || !NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            handler.post(() -> callback.onResult(false));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Capture self-test", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Silent probe used to verify notification capture");
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }

        MyNotificationListenerService.resetSelfTest();
        final long probeMillis = System.currentTimeMillis();

        Intent open = new Intent(app, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(app, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle(TITLE)
                .setContentText("Silent probe \u2014 safe to ignore")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            nm.notify(NOTIF_ID, builder.build());
        } catch (SecurityException e) {
            handler.post(() -> callback.onResult(false));
            return;
        }

        handler.postDelayed(() -> {
            nm.cancel(NOTIF_ID);
            boolean captured = MyNotificationListenerService.getSelfTestSeenAt() >= probeMillis;
            if (!captured) {
                // Fallback: any capture after the probe also proves the listener is alive.
                captured = MyNotificationListenerService.getLastCaptureAt() >= probeMillis;
            }
            callback.onResult(captured);
        }, GRACE_MS);
    }
}