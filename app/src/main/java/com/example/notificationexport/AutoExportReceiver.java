package com.example.notificationexport;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Fired by the daily alarm: writes the filtered log to the export folder. */
public class AutoExportReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "auto_export_channel";
    private static final int NOTIF_ID = 2001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ScheduleManager.isEnabled(context)) return;
        exportNow(context);
    }

    /** Runs the export immediately. Returns the number of rows written, or -1 when nothing was saved. */
    public static int exportNow(Context context) {
        File raw = new File(context.getFilesDir(), "notification_history.csv");
        if (!raw.exists() || raw.length() == 0) return -1;

        FilterConfig cfg = FilterConfig.load(context);
        File dir = ScheduleManager.getExportDir(context);
        if (!dir.exists() && !dir.mkdirs()) return -1;

        String name = "notifications_"
                + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date())
                + ".csv";
        File out = new File(dir, name);

        CsvFilter.Result result = CsvFilter.writeSince(raw, out, cfg, ScheduleManager.getLastExportMillis(context));
        if (result == null || result.kept == 0) return -1;

        ScheduleManager.setLastExportMillis(context, result.newestMillis);
        notifySaved(context, result.kept, name);
        return result.kept;
    }

    private static void notifySaved(Context context, int rows, String fileName) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Auto export", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when a scheduled CSV export is saved");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("Auto-export saved")
                .setContentText(rows + " rows \u2192 " + fileName)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi);

        nm.notify(NOTIF_ID, builder.build());
    }
}