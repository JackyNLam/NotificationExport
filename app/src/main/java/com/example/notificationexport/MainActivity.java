package com.example.notificationexport;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int STALE_MINUTES = 30;

    private TextView tvStatus;
    private TextView tvScheduleInfo;

    // Working copy of the filter rules while the dialog is open
    private FilterConfig draft = new FilterConfig();

    private Button btnSelectExcludeApps;
    private Button btnSelectIncludeApps;
    private Button btnSelectExcludeTitles;
    private Button btnSelectIncludeTitles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        );

        tvStatus = findViewById(R.id.tvListenerStatus);
        tvScheduleInfo = findViewById(R.id.tvScheduleInfo);

        applyWindowInsets();

        Button btnEnable = findViewById(R.id.btnEnableListener);
        Button btnExport = findViewById(R.id.btnExportLog);
        Button btnFilterExport = findViewById(R.id.btnFilterExport);
        Button btnSchedule = findViewById(R.id.btnSchedule);
        Button btnClear = findViewById(R.id.btnClearLog);

        btnEnable.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            Toast.makeText(this, "Toggle ON 'Notification Exporter' in the list", Toast.LENGTH_LONG).show();
        });

        btnExport.setOnClickListener(v -> exportFile());
        btnFilterExport.setOnClickListener(v -> showFilterDialog());
        btnSchedule.setOnClickListener(v -> showScheduleDialog());

        btnClear.setOnClickListener(v -> showClearDialog());

        requestNotificationPermissionIfNeeded();

        // Keep the background capture check alive.
        HealthCheckJobService.ensureScheduled(this);
        ScheduleManager.applySchedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
        updateScheduleInfo();
        checkAndPromptIfStale();
    }

    /**
     * Apps targeting SDK 35+ draw edge-to-edge, so the layout has to pad itself
     * clear of the status bar and navigation bar.
     */
    private void applyWindowInsets() {
        final View content = findViewById(R.id.contentLayout);
        if (content == null) return;

        final int side = dp(24);
        final int top = dp(24);
        final int bottom = dp(24);

        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(side, top + bars.top, side, bottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    // ------------------------------------------------------------------
    //  Status
    // ------------------------------------------------------------------

    private void updateStatusUI() {
        boolean connected = isListenerEnabled();
        String lastTime = getLastNotificationTime();
        if (connected) {
            if (lastTime != null) {
                if (isStale(lastTime, STALE_MINUTES)) {
                    tvStatus.setText("\u26A0\uFE0F No capture for " + STALE_MINUTES + " min | Last: " + lastTime);
                    tvStatus.setTextColor(Color.parseColor("#FF9800"));
                } else {
                    tvStatus.setText("\u2705 Listening | Last: " + lastTime);
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                }
            } else {
                tvStatus.setText("\u2705 Listening | Waiting for first notification...");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            }
        } else {
            tvStatus.setText("\u274C Disconnected | Last: " + (lastTime != null ? lastTime : "None"));
            tvStatus.setTextColor(Color.parseColor("#F44336"));
        }
    }

    private void updateScheduleInfo() {
        if (ScheduleManager.isEnabled(this)) {
            String time = String.format(Locale.US, "%02d:%02d",
                    ScheduleManager.getHour(this), ScheduleManager.getMinute(this));
            int days = ScheduleManager.getIntervalDays(this);
            String when = days == 1 ? "Daily at " + time : "Every " + days + " days at " + time;
            long next = ScheduleManager.getNextFireMillis(this);
            String nextLine = next > 0
                    ? "\nNext: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(next))
                    : "";
            tvScheduleInfo.setText("⏰ " + when + nextLine + "\nFolder: "
                    + ScheduleManager.getExportDirDisplayPath(this));
        } else {
            tvScheduleInfo.setText("⏰ Auto-export off");
        }
        tvScheduleInfo.setTextSize(12);
        tvScheduleInfo.setTextColor(Color.parseColor("#757575"));
    }

    private void checkAndPromptIfStale() {
        String lastTimeStr = getLastNotificationTime();
        if (lastTimeStr != null && isStale(lastTimeStr, STALE_MINUTES)) {
            new AlertDialog.Builder(this)
                    .setTitle("Notification Access Check")
                    .setMessage("It has been over " + STALE_MINUTES + " minutes since the last "
                            + "notification was captured. The listener might have disconnected.\n\n"
                            + "The app also self-tests in the background every 30 minutes and will "
                            + "alert you if capture has stopped.")
                    .setPositiveButton("Go to Settings", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    private String getLastNotificationTime() {
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return null;
        String lastTime = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("Timestamp") || line.startsWith("\uFEFF")) continue;
                if (line.startsWith("\"")) {
                    int endQuote = line.indexOf("\"", 1);
                    if (endQuote > 1) lastTime = line.substring(1, endQuote);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lastTime;
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private boolean isStale(String lastTimeStr, int minutes) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date lastDate = sdf.parse(lastTimeStr);
            if (lastDate == null) return false;
            return (System.currentTimeMillis() - lastDate.getTime()) > (minutes * 60 * 1000L);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Exports
    // ------------------------------------------------------------------

    private void exportFile() {
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists() || csvFile.length() <= 40) {
            Toast.makeText(this, "No notifications captured yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", csvFile);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share CSV Notification History"));
    }

    /**
     * Writes the filtered log for {@code [fromMillis, toMillis]} and shares it.
     * A zero bound means "no limit"; both zero means the whole log.
     */
    private void exportFiltered(long fromMillis, long toMillis) {
        File rawFile = new File(getFilesDir(), "notification_history.csv");
        if (!rawFile.exists() || rawFile.length() <= 40) {
            Toast.makeText(this, "No raw data to filter.", Toast.LENGTH_SHORT).show();
            return;
        }

        FilterConfig cfg = FilterConfig.load(this);
        File filteredFile = new File(getCacheDir(), "notification_history_filtered.csv");
        CsvFilter.Result result = CsvFilter.writeRange(rawFile, filteredFile, cfg, fromMillis, toMillis, 0);

        if (result == null || result.kept == 0) {
            Toast.makeText(this, "\u26A0\uFE0F No rows match that filter/period.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", filteredFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Filtered CSV (" + result.kept + " rows)"));
            Toast.makeText(this, "\u2705 Kept: " + result.kept + " | Skipped: " + result.skipped,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "\u274C Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    //  Auto-export schedule
    // ------------------------------------------------------------------

    private void showScheduleDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        CheckBox chkEnable = new CheckBox(this);
        chkEnable.setText("Enable scheduled auto-export (filtered)");
        chkEnable.setChecked(ScheduleManager.isEnabled(this));
        layout.addView(chkEnable);

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvEvery = new TextView(this);
        tvEvery.setText("Every ");
        intervalRow.addView(tvEvery);

        final EditText etDays = new EditText(this);
        etDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDays.setText(String.valueOf(ScheduleManager.getIntervalDays(this)));
        etDays.setEms(3);
        intervalRow.addView(etDays);

        TextView tvDays = new TextView(this);
        tvDays.setText(" day(s)");
        intervalRow.addView(tvDays);

        layout.addView(intervalRow);

        int[] time = {ScheduleManager.getHour(this), ScheduleManager.getMinute(this)};
        Button btnTime = new Button(this);
        btnTime.setAllCaps(false);
        btnTime.setText(timeText(time[0], time[1]));
        btnTime.setOnClickListener(v -> new TimePickerDialog(this, (view, h, m) -> {
            time[0] = h;
            time[1] = m;
            btnTime.setText(timeText(h, m));
        }, time[0], time[1], true).show());
        layout.addView(btnTime);

        TextView tvPath = new TextView(this);
        tvPath.setText("Saved to:\n" + ScheduleManager.getExportDirDisplayPath(this)
                + "\n\nEach run only writes rows that were never exported before, so nothing is duplicated.");
        tvPath.setTextSize(12);
        tvPath.setTextColor(Color.GRAY);
        tvPath.setPadding(0, 16, 0, 8);
        layout.addView(tvPath);

        new AlertDialog.Builder(this)
                .setTitle("\u23F0 Auto-Export Schedule")
                .setView(layout)
                .setPositiveButton("Save", (d, which) -> {
                    int days = 1;
                    try {
                        days = Integer.parseInt(etDays.getText().toString().trim());
                    } catch (Exception ignored) {
                        // keep default
                    }
                    if (days < 1) days = 1;
                    ScheduleManager.save(this, chkEnable.isChecked(), time[0], time[1], days);
                    updateScheduleInfo();
                    Toast.makeText(this, chkEnable.isChecked()
                            ? "\u2705 " + (days == 1 ? "Daily" : "Every " + days + " days")
                              + " at " + timeText(time[0], time[1])
                            : "Auto-export disabled", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Self-test now", (d, which) -> runSelfTest())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String timeText(int hour, int minute) {
        return String.format(Locale.US, "\u23F1 Export time: %02d:%02d", hour, minute);
    }

    private void runSelfTest() {
        Toast.makeText(this, "Sending a silent test notification...", Toast.LENGTH_SHORT).show();
        SelfTest.run(this, captured -> {
            Toast.makeText(this,
                    captured
                            ? "\u2705 Capture is working"
                            : "\u274C Test notification was not captured \u2014 re-toggle notification access",
                    Toast.LENGTH_LONG).show();
            offerDebugExport();
        });
    }

    /** Writes a copy of the real captured data for testing, without touching the export cursor. */
    private void offerDebugExport() {
        File debug = DebugExport.write(this);
        if (debug == null) {
            Toast.makeText(this, "No captured data yet \u2014 debug CSV skipped", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Debug CSV written")
                .setMessage(debug.getAbsolutePath()
                        + "\n\nA copy of the real captured rows (no filter applied). "
                        + "It is separate from scheduled exports, and these rows can still "
                        + "appear in future scheduled exports.")
                .setPositiveButton("Share", (d, which) -> shareFile(debug, "Share debug CSV"))
                .setNegativeButton("OK", null)
                .show();
    }

    private void shareFile(File file, String chooserTitle) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(this, "\u274C Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    //  Clear log (with cutoff + unexported-data protection)
    // ------------------------------------------------------------------

    private void showClearDialog() {
        final File raw = new File(getFilesDir(), "notification_history.csv");
        if (!raw.exists() || CsvFilter.countDataRows(raw) == 0) {
            Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show();
            return;
        }

        long newest = CsvFilter.lastTimestampMillis(this);
        // Default: keep today's rows, delete everything from earlier days.
        final long[] cutoff = {startOfDay(newest > 0 ? newest : System.currentTimeMillis())};

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("Rows captured before the chosen day are deleted. "
                + "Rows from that day onward are kept.");
        tvInfo.setTextSize(13);
        layout.addView(tvInfo);

        Button btnCutoff = new Button(this);
        btnCutoff.setAllCaps(false);
        btnCutoff.setText(cutoffText(cutoff[0]));
        btnCutoff.setOnClickListener(v -> pickDay(cutoff[0] > 0 ? cutoff[0] - 1 : 0, true, millis -> {
            cutoff[0] = millis;
            btnCutoff.setText(cutoffText(millis));
        }));
        layout.addView(btnCutoff);

        CheckBox chkAll = new CheckBox(this);
        chkAll.setText("Clear the entire log instead");
        chkAll.setOnCheckedChangeListener((b, checked) -> setControlEnabled(btnCutoff, !checked));
        layout.addView(chkAll);

        final int cursor = ScheduleManager.getExportedRowCount(this);
        CsvFilter.PurgeResult preview = CsvFilter.analyzePurge(raw, cutoff[0], cursor);
        TextView tvCount = new TextView(this);
        tvCount.setText(preview == null ? ""
                : preview.removed + " row(s) would be deleted, " + preview.remaining + " kept.");
        tvCount.setTextSize(12);
        tvCount.setTextColor(Color.parseColor("#757575"));
        tvCount.setPadding(0, 12, 0, 0);
        layout.addView(tvCount);

        new AlertDialog.Builder(this)
                .setTitle("Clear Log History")
                .setView(layout)
                .setPositiveButton("Clear", (d, which) ->
                        confirmPurge(raw, chkAll.isChecked() ? Long.MAX_VALUE : cutoff[0],
                                chkAll.isChecked()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPurge(File raw, long cutoffMillis, boolean all) {
        int cursor = ScheduleManager.getExportedRowCount(this);
        CsvFilter.PurgeResult preview = CsvFilter.analyzePurge(raw, cutoffMillis, cursor);
        if (preview == null) {
            Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if (preview.removedUnexported > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("\u26A0\uFE0F Not exported yet")
                    .setMessage(preview.removedUnexported + " of the " + preview.removed
                            + " row(s) being deleted have never been written to an auto-export. "
                            + "Once deleted they can never appear in a future export.\n\n"
                            + "Export them first if you want to keep them.")
                    .setPositiveButton("Delete anyway", (d, which) ->
                            performPurge(raw, cutoffMillis, all))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        performPurge(raw, cutoffMillis, all);
    }

    private void performPurge(File raw, long cutoffMillis, boolean all) {
        if (all) {
            if (raw.delete()) {
                ScheduleManager.resetCursor(this);
                Toast.makeText(this, "\u2705 Log cleared", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not clear the log", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        int cursor = ScheduleManager.getExportedRowCount(this);
        CsvFilter.PurgeResult result = CsvFilter.purgeBefore(raw, cutoffMillis, cursor);
        if (result == null) {
            Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        ScheduleManager.setExportedRowCount(this, result.newCursor);
        Toast.makeText(this, "\u2705 Deleted " + result.removed + " row(s), kept "
                + result.remaining, Toast.LENGTH_LONG).show();
    }

    private static String cutoffText(long millis) {
        return "Delete rows before: " + new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(millis));
    }

    // ------------------------------------------------------------------
    //  Filter dialog
    // ------------------------------------------------------------------

    private void showFilterDialog() {
        draft = FilterConfig.load(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        CheckBox chkSkipEmpty = new CheckBox(this);
        chkSkipEmpty.setText("Skip empty / N/A messages");
        chkSkipEmpty.setChecked(draft.skipEmpty);
        chkSkipEmpty.setOnCheckedChangeListener((btn, isChecked) -> draft.skipEmpty = isChecked);
        layout.addView(chkSkipEmpty);

        // ---- Period: from day .. to day (defaults to the whole captured log) ----
        layout.addView(spacer());

        TextView tvPeriodLabel = new TextView(this);
        tvPeriodLabel.setText("\uD83D\uDCC5 Period (days)");
        tvPeriodLabel.setTextSize(13);
        tvPeriodLabel.setTextColor(Color.parseColor("#616161"));
        layout.addView(tvPeriodLabel);

        long firstRow = CsvFilter.firstTimestampMillis(this);
        final long[] range = {
                firstRow > 0 ? startOfDay(firstRow) : 0L,
                endOfDay(System.currentTimeMillis())
        };

        Button btnFrom = new Button(this);
        btnFrom.setAllCaps(false);
        btnFrom.setText(fromText(range[0]));
        btnFrom.setOnClickListener(v -> pickDay(range[0], true, millis -> {
            range[0] = millis;
            btnFrom.setText(fromText(range[0]));
        }));
        layout.addView(btnFrom);

        Button btnTo = new Button(this);
        btnTo.setAllCaps(false);
        btnTo.setText(toText(range[1]));
        btnTo.setOnClickListener(v -> pickDay(range[1], false, millis -> {
            range[1] = millis;
            btnTo.setText(toText(range[1]));
        }));
        layout.addView(btnTo);

        layout.addView(spacer());

        CheckBox chkIncludePkgs = new CheckBox(this);
        chkIncludePkgs.setText("Apps: include only (ignore exclude list)");
        chkIncludePkgs.setChecked(draft.includeOnlyPkgs);
        chkIncludePkgs.setOnCheckedChangeListener((btn, isChecked) -> {
            draft.includeOnlyPkgs = isChecked;
            refreshFilterButtonStates();
        });
        layout.addView(chkIncludePkgs);

        btnSelectExcludeApps = new Button(this);
        btnSelectExcludeApps.setAllCaps(false);
        btnSelectExcludeApps.setText(appButtonText(false));
        btnSelectExcludeApps.setOnClickListener(v -> showPackageSelectionDialog(false));
        layout.addView(btnSelectExcludeApps);

        btnSelectIncludeApps = new Button(this);
        btnSelectIncludeApps.setAllCaps(false);
        btnSelectIncludeApps.setText(appButtonText(true));
        btnSelectIncludeApps.setOnClickListener(v -> showPackageSelectionDialog(true));
        layout.addView(btnSelectIncludeApps);

        layout.addView(spacer());

        CheckBox chkIncludeTitles = new CheckBox(this);
        chkIncludeTitles.setText("Messages: include only (ignore exclude list)");
        chkIncludeTitles.setChecked(draft.includeOnlyTitles);
        chkIncludeTitles.setOnCheckedChangeListener((btn, isChecked) -> {
            draft.includeOnlyTitles = isChecked;
            refreshFilterButtonStates();
        });
        layout.addView(chkIncludeTitles);

        btnSelectExcludeTitles = new Button(this);
        btnSelectExcludeTitles.setAllCaps(false);
        btnSelectExcludeTitles.setText(titleButtonText(false));
        btnSelectExcludeTitles.setOnClickListener(v -> showTitleSelectionDialog(false));
        layout.addView(btnSelectExcludeTitles);

        btnSelectIncludeTitles = new Button(this);
        btnSelectIncludeTitles.setAllCaps(false);
        btnSelectIncludeTitles.setText(titleButtonText(true));
        btnSelectIncludeTitles.setOnClickListener(v -> showTitleSelectionDialog(true));
        layout.addView(btnSelectIncludeTitles);

        Button btnResetFilters = new Button(this);
        btnResetFilters.setAllCaps(false);
        btnResetFilters.setText("\u21BA Reset filters");
        layout.addView(btnResetFilters);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("\u2139\uFE0F Reused by the scheduled auto-export once saved.");
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(Color.GRAY);
        tvInfo.setPadding(0, 24, 0, 0);
        layout.addView(tvInfo);

        refreshFilterButtonStates();

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDD0D Filter and Period Before Export")
                .setView(layout)
                .setPositiveButton("Save", (d, which) -> {
                    draft.save(this);
                    Toast.makeText(this, "\u2705 Filter preferences saved", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Export Filtered", (d, which) -> {
                    draft.save(this);
                    exportFiltered(range[0], range[1]);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        btnResetFilters.setOnClickListener(v -> {
            FilterConfig.reset(this);
            Toast.makeText(this, "\u2705 Filter preferences reset", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            showFilterDialog();
        });
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24));
        return v;
    }

    // ---- Day-range helpers for the manual export period ----

    private interface DayPicked {
        void onPicked(long millis);
    }

    private void pickDay(long current, boolean isFrom, DayPicked callback) {
        Calendar seed = Calendar.getInstance();
        if (current > 0) seed.setTimeInMillis(current);
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, day, 0, 0, 0);
            picked.set(Calendar.MILLISECOND, 0);
            long millis = picked.getTimeInMillis();
            callback.onPicked(isFrom ? startOfDay(millis) : endOfDay(millis));
        }, seed.get(Calendar.YEAR), seed.get(Calendar.MONTH),
                seed.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long endOfDay(long millis) {
        return startOfDay(millis) + 24L * 60L * 60L * 1000L - 1L;
    }

    private static String dayText(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    private static String fromText(long millis) {
        return millis > 0 ? "From: " + dayText(millis) : "From: any day";
    }

    private static String toText(long millis) {
        return millis > 0 ? "To: " + dayText(millis) : "To: any day";
    }

    /** An "include only" toggle makes the opposite list inapplicable. */
    private void refreshFilterButtonStates() {
        setControlEnabled(btnSelectIncludeApps, draft.includeOnlyPkgs);
        setControlEnabled(btnSelectExcludeApps, !draft.includeOnlyPkgs);
        setControlEnabled(btnSelectIncludeTitles, draft.includeOnlyTitles);
        setControlEnabled(btnSelectExcludeTitles, !draft.includeOnlyTitles);
    }

    private void setControlEnabled(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    private String appButtonText(boolean include) {
        int count = include ? draft.includedPkgs.size() : draft.excludedPkgs.size();
        return include
                ? "\u2705 Include Only Apps (" + count + " selected)"
                : "\uD83D\uDCF1 Exclude Apps (" + count + " selected)";
    }

    private String titleButtonText(boolean include) {
        int count = include ? draft.includedTitles.size() : draft.excludedTitles.size();
        return include
                ? "\u2705 Include Only Messages (" + count + " selected)"
                : "\uD83D\uDCAC Exclude Messages (" + count + " selected)";
    }

    private void showPackageSelectionDialog(final boolean include) {
        // The app list follows the message selection: only apps that posted one of
        // the chosen titles (include or exclude, whichever mode is active) are offered.
        final Set<String> anchorTitles = draft.includeOnlyTitles
                ? draft.includedTitles : draft.excludedTitles;
        final List<String> allPkgs = extractUniquePackagesByFrequency(anchorTitles);
        if (allPkgs.isEmpty()) {
            Toast.makeText(this, anchorTitles.isEmpty()
                    ? "No apps found in log yet."
                    : "No apps found for the selected messages.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> target = include ? draft.includedPkgs : draft.excludedPkgs;
        final Set<String> previous = new HashSet<>(target);

        String[] displayNames = new String[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            String pkg = allPkgs.get(i);
            String appName = getAppName(pkg);
            displayNames[i] = appName.equals(pkg) ? pkg : appName + "\n" + pkg;
        }

        final boolean[] checkedItems = new boolean[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            checkedItems[i] = equalsIgnoreCase(previous, allPkgs.get(i));
        }

        String title = (include ? "Include only these apps (" : "Exclude these apps (")
                + allPkgs.size() + ")";
        if (!anchorTitles.isEmpty()) {
            title += "\nFiltered by " + (draft.includeOnlyTitles ? "included" : "excluded")
                    + " messages";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(displayNames, checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    target.clear();
                    for (int i = 0; i < allPkgs.size(); i++) {
                        if (checkedItems[i]) target.add(allPkgs.get(i));
                    }
                    // selections that the cascade no longer lists must survive
                    for (String kept : previous) {
                        if (!containsIgnoreCase(allPkgs, kept)) target.add(kept);
                    }
                    draft.save(this);
                    Button b = include ? btnSelectIncludeApps : btnSelectExcludeApps;
                    if (b != null) b.setText(appButtonText(include));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTitleSelectionDialog(final boolean include) {
        // The message list follows the app selection, so only titles that came from
        // the chosen apps are offered (the same rule in include and exclude mode).
        final Set<String> anchorPkgs = draft.includeOnlyPkgs
                ? draft.includedPkgs : draft.excludedPkgs;
        final List<String> allTitles = extractUniqueTitlesByFrequency(anchorPkgs);
        if (allTitles.isEmpty()) {
            Toast.makeText(this, anchorPkgs.isEmpty()
                    ? "No messages found in log yet."
                    : "No messages found for the selected apps.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> target = include ? draft.includedTitles : draft.excludedTitles;
        final Set<String> previous = new HashSet<>(target);

        String[] displayNames = new String[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            String t = allTitles.get(i);
            displayNames[i] = t.length() > 100 ? t.substring(0, 97) + "..." : t;
        }

        final boolean[] checkedItems = new boolean[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            checkedItems[i] = equalsIgnoreCase(previous, allTitles.get(i));
        }

        String title = (include ? "Include only these messages (" : "Exclude these messages (")
                + allTitles.size() + ")";
        if (!anchorPkgs.isEmpty()) {
            title += "\nFiltered by " + (draft.includeOnlyPkgs ? "included" : "excluded")
                    + " apps";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(displayNames, checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    target.clear();
                    for (int i = 0; i < allTitles.size(); i++) {
                        if (checkedItems[i]) target.add(allTitles.get(i));
                    }
                    for (String kept : previous) {
                        if (!containsIgnoreCase(allTitles, kept)) target.add(kept);
                    }
                    draft.save(this);
                    Button b = include ? btnSelectIncludeTitles : btnSelectExcludeTitles;
                    if (b != null) b.setText(titleButtonText(include));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    //  Log scanning helpers
    // ------------------------------------------------------------------

    private List<String> extractUniquePackagesByFrequency(Set<String> allowedTitles) {
        Map<String, Integer> freq = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = CsvFilter.parseCsvLine(line);
                if (parts.length < 3) continue;
                String pkg = parts[1].trim();
                String title = parts[2].trim();
                if (pkg.isEmpty()) continue;
                if (allowedTitles != null && !allowedTitles.isEmpty()
                        && !equalsIgnoreCase(allowedTitles, title)) continue;
                Integer count = freq.get(pkg);
                freq.put(pkg, count == null ? 1 : count + 1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sortByFrequencyDesc(freq, Integer.MAX_VALUE);
    }

    private List<String> extractUniqueTitlesByFrequency(Set<String> allowedPkgs) {
        Map<String, Integer> freq = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = CsvFilter.parseCsvLine(line);
                if (parts.length < 3) continue;
                String pkg = parts[1].trim();
                String title = parts[2].trim();
                if (title.isEmpty()) continue;
                if (allowedPkgs != null && !allowedPkgs.isEmpty()
                        && !equalsIgnoreCase(allowedPkgs, pkg)) continue;
                Integer count = freq.get(title);
                freq.put(title, count == null ? 1 : count + 1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sortByFrequencyDesc(freq, 2000);
    }

    private static boolean equalsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private List<String> sortByFrequencyDesc(Map<String, Integer> freq, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        List<String> result = new ArrayList<>();
        int cap = Math.min(entries.size(), limit);
        for (int i = 0; i < cap; i++) result.add(entries.get(i).getKey());
        return result;
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}