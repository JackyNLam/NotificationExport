package com.example.notificationexport;

import android.Manifest;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

        btnClear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear Log History")
                .setMessage("Delete all captured notifications?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    File csvFile = new File(getFilesDir(), "notification_history.csv");
                    if (csvFile.exists() && csvFile.delete()) {
                        ScheduleManager.setLastExportMillis(this, 0L);
                        Toast.makeText(this, "\u2705 Log cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());

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
            tvScheduleInfo.setText("⏰ Daily at " + time + "\nFolder: "
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

    private void exportFiltered() {
        File rawFile = new File(getFilesDir(), "notification_history.csv");
        if (!rawFile.exists() || rawFile.length() <= 40) {
            Toast.makeText(this, "No raw data to filter.", Toast.LENGTH_SHORT).show();
            return;
        }

        FilterConfig cfg = FilterConfig.load(this);
        File filteredFile = new File(getCacheDir(), "notification_history_filtered.csv");
        CsvFilter.Result result = CsvFilter.writeSince(rawFile, filteredFile, cfg, 0L);

        if (result == null || result.kept == 0) {
            Toast.makeText(this, "\u26A0\uFE0F All rows filtered out.", Toast.LENGTH_SHORT).show();
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
        chkEnable.setText("Enable daily auto-export (filtered)");
        chkEnable.setChecked(ScheduleManager.isEnabled(this));
        layout.addView(chkEnable);

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
                + "\n\nOnly rows not yet exported are written, using your current filter.");
        tvPath.setTextSize(12);
        tvPath.setTextColor(Color.GRAY);
        tvPath.setPadding(0, 16, 0, 8);
        layout.addView(tvPath);

        new AlertDialog.Builder(this)
                .setTitle("\u23F0 Auto-Export Schedule")
                .setView(layout)
                .setPositiveButton("Save", (d, which) -> {
                    ScheduleManager.save(this, chkEnable.isChecked(), time[0], time[1]);
                    updateScheduleInfo();
                    Toast.makeText(this, chkEnable.isChecked()
                            ? "\u2705 Daily export at " + timeText(time[0], time[1])
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
        SelfTest.run(this, captured -> Toast.makeText(this,
                captured
                        ? "\u2705 Capture is working"
                        : "\u274C Test notification was not captured \u2014 re-toggle notification access",
                Toast.LENGTH_LONG).show());
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

        layout.addView(spacer());

        CheckBox chkIncludePkgs = new CheckBox(this);
        chkIncludePkgs.setText("Apps: include only (ignore exclude list)");
        chkIncludePkgs.setChecked(draft.includeOnlyPkgs);
        chkIncludePkgs.setOnCheckedChangeListener((btn, isChecked) -> draft.includeOnlyPkgs = isChecked);
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
        chkIncludeTitles.setOnCheckedChangeListener((btn, isChecked) -> draft.includeOnlyTitles = isChecked);
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

        TextView tvInfo = new TextView(this);
        tvInfo.setText("\u2139\uFE0F Saved automatically and reused by the daily auto-export.");
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(Color.GRAY);
        tvInfo.setPadding(0, 24, 0, 0);
        layout.addView(tvInfo);

        new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDD0D Filter Before Export")
                .setView(layout)
                .setPositiveButton("Export Filtered", (d, which) -> {
                    draft.save(this);
                    exportFiltered();
                })
                .setNeutralButton("Reset", (d, which) -> {
                    FilterConfig.reset(this);
                    Toast.makeText(this, "\u2705 Filter preferences reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24));
        return v;
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
        final List<String> allPkgs = extractUniquePackagesByFrequency();
        if (allPkgs.isEmpty()) {
            Toast.makeText(this, "No apps found in log yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> target = include ? draft.includedPkgs : draft.excludedPkgs;

        String[] displayNames = new String[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            String pkg = allPkgs.get(i);
            String appName = getAppName(pkg);
            displayNames[i] = appName.equals(pkg) ? pkg : appName + "\n" + pkg;
        }

        final boolean[] checkedItems = new boolean[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            checkedItems[i] = target.contains(allPkgs.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle((include ? "Include only these apps (" : "Exclude these apps (")
                        + allPkgs.size() + ")")
                .setMultiChoiceItems(displayNames, checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    target.clear();
                    for (int i = 0; i < allPkgs.size(); i++) {
                        if (checkedItems[i]) target.add(allPkgs.get(i));
                    }
                    Button b = include ? btnSelectIncludeApps : btnSelectExcludeApps;
                    if (b != null) b.setText(appButtonText(include));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTitleSelectionDialog(final boolean include) {
        final List<String> allTitles = extractUniqueTitlesByFrequency();
        if (allTitles.isEmpty()) {
            Toast.makeText(this, "No messages found in log yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> target = include ? draft.includedTitles : draft.excludedTitles;

        String[] displayNames = new String[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            String t = allTitles.get(i);
            displayNames[i] = t.length() > 100 ? t.substring(0, 97) + "..." : t;
        }

        final boolean[] checkedItems = new boolean[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            checkedItems[i] = target.contains(allTitles.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle((include ? "Include only these messages (" : "Exclude these messages (")
                        + allTitles.size() + ")")
                .setMultiChoiceItems(displayNames, checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    target.clear();
                    for (int i = 0; i < allTitles.size(); i++) {
                        if (checkedItems[i]) target.add(allTitles.get(i));
                    }
                    Button b = include ? btnSelectIncludeTitles : btnSelectExcludeTitles;
                    if (b != null) b.setText(titleButtonText(include));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    //  Log scanning helpers
    // ------------------------------------------------------------------

    private List<String> extractUniquePackagesByFrequency() {
        Map<String, Integer> freq = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = CsvFilter.parseCsvLine(line);
                if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                    String pkg = parts[1].trim();
                    Integer count = freq.get(pkg);
                    freq.put(pkg, count == null ? 1 : count + 1);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sortByFrequencyDesc(freq, Integer.MAX_VALUE);
    }

    private List<String> extractUniqueTitlesByFrequency() {
        Map<String, Integer> freq = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = CsvFilter.parseCsvLine(line);
                if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
                    String title = parts[2].trim();
                    Integer count = freq.get(title);
                    freq.put(title, count == null ? 1 : count + 1);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sortByFrequencyDesc(freq, 2000);
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