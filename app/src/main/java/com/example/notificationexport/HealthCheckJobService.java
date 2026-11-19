package com.example.notificationexport;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Every 30 minutes: if nothing was captured in the last 30 minutes, probe the
 * listener with a silent notification. If the probe is not captured either,
 * raise a visible alert so the user can re-toggle notification access.
 */
public class HealthCheckJobService extends JobService {

    public static final int JOB_ID = 4101;
    private static final long WINDOW_MS = 30L * 60L * 1000L;
    private static final long PERIOD_MS = WINDOW_MS;
    private static final String ALERT_CHANNEL_ID = "capture_problem_channel";
    private static final int ALERT_ID = 3101;

    @Override
    public boolean onStartJob(final JobParameters params) {
        long last = Math.max(
                MyNotificationListenerService.getLastCaptureAt(),
                CsvFilter.lastTimestampMillis(this));

        if (System.currentTimeMillis() - last < WINDOW_MS) {
            return false;
        }

        SelfTest.run(this, captured -> {
            if (captured) {
                cancelProblemAlert();
            } else {
                postProblemAlert();
            }
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private void createAlertChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "Capture problems", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Warns when notification capture stops working");
            nm.createNotificationChannel(channel);
        }
    }

    private void postProblemAlert() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        createAlertChannel(nm);

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("\u26A0\uFE0F Notification capture may be broken")
                .setContentText("Nothing captured and a test notification was missed. Tap to fix.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "Nothing has been captured for 30 minutes, and a silent test notification "
                                + "was not captured either. Your phone may have unbound the listener. "
                                + "Tap here, then toggle Notification Exporter OFF and ON in the "
                                + "notification access settings."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        nm.notify(ALERT_ID, builder.build());
    }

    private void cancelProblemAlert() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ALERT_ID);
    }

    /** Idempotent: scheduling the same job id again simply replaces it. */
    public static void ensureScheduled(Context ctx) {
        JobScheduler scheduler = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(ctx, HealthCheckJobService.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build();

        try {
            scheduler.schedule(job);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}