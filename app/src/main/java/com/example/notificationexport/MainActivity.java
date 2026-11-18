package com.example.notificationexport;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import android.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import android.widget.TextView;
import android.graphics.Color;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;

    // Filter preference keys
    private static final String PREFS_NAME = "filter_prefs";
    private static final String PREF_SKIP_EMPTY = "skip_empty";
    private static final String PREF_EXCLUDED_PKGS = "excluded_pkgs";
    private static final String PREF_EXCLUDED_TITLES = "excluded_titles";

    // Current dialog filter state
    private boolean dialogSkipEmpty = true;
    private Set<String> dialogExcludedPkgs = new HashSet<>();
    private Set<String> dialogExcludedTitles = new HashSet<>();

    // UI references for in-place button text updates
    private Button btnSelectApps;
    private Button btnSelectTitles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(android.graphics.Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        );

        tvStatus = findViewById(R.id.tvListenerStatus);
        Button btnEnable = findViewById(R.id.btnEnableListener);
        Button btnExport = findViewById(R.id.btnExportLog);

        btnEnable.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Toggle ON 'Notification Exporter' in the list", Toast.LENGTH_LONG).show();
        });

        btnExport.setOnClickListener(v -> exportFile());

        Button btnClear = findViewById(R.id.btnClearLog);
        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Log History")
                    .setMessage("Delete all captured notifications?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        File csvFile = new File(getFilesDir(), "notification_history.csv");
                        if (csvFile.exists() && csvFile.delete()) {
                            Toast.makeText(this, "\u2705 Log cleared", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        Button btnFilterExport = findViewById(R.id.btnFilterExport);
        btnFilterExport.setOnClickListener(v -> showFilterDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
        checkAndPromptIfStale();
    }

    private void updateStatusUI() {
        boolean connected = isListenerEnabled();
        String lastTime = getLastNotificationTime();
        if (connected) {
            if (lastTime != null) {
                if (isStale(lastTime, 15)) {
                    tvStatus.setText("\u26A0\uFE0F Stale | Last: " + lastTime);
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

    private void checkAndPromptIfStale() {
        String lastTimeStr = getLastNotificationTime();
        if (lastTimeStr != null && isStale(lastTimeStr, 15)) {
            new AlertDialog.Builder(this)
                    .setTitle("Notification Access Check")
                    .setMessage("It has been over 15 minutes since the last notification was captured. The listener might have disconnected.\n\nPlease toggle 'Notification Exporter' OFF and ON in the settings to fix it.")
                    .setPositiveButton("Go to Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                        startActivity(intent);
                    })
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
                if (line.isEmpty() || line.startsWith("Timestamp")) continue;
                if (line.startsWith("\"")) {
                    int endQuote = line.indexOf("\"", 1);
                    if (endQuote > 1) {
                        lastTime = line.substring(1, endQuote);
                    }
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
            long diffInMillis = System.currentTimeMillis() - lastDate.getTime();
            return diffInMillis > (minutes * 60 * 1000);
        } catch (Exception e) {
            return false;
        }
    }

    private void exportFile() {
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists() || csvFile.length() <= 40) {
            Toast.makeText(this, "No notifications captured yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                csvFile
        );
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share CSV Notification History"));
    }

    // ===============================================================
    //  ENHANCED FILTER DIALOG WITH MULTI-SELECT + SAVED PREFERENCES
    // ===============================================================

    private void showFilterDialog() {
        loadFilterPreferences();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        CheckBox chkSkipEmpty = new CheckBox(this);
        chkSkipEmpty.setText("Skip empty / N/A messages");
        chkSkipEmpty.setChecked(dialogSkipEmpty);
        chkSkipEmpty.setOnCheckedChangeListener((btn, isChecked) -> dialogSkipEmpty = isChecked);
        layout.addView(chkSkipEmpty);

        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 32));
        layout.addView(spacer);

        btnSelectApps = new Button(this);
        btnSelectApps.setAllCaps(false);
        btnSelectApps.setText(getAppButtonText());
        btnSelectApps.setOnClickListener(v -> showPackageSelectionDialog());
        layout.addView(btnSelectApps);

        btnSelectTitles = new Button(this);
        btnSelectTitles.setAllCaps(false);
        btnSelectTitles.setText(getTitleButtonText());
        btnSelectTitles.setOnClickListener(v -> showTitleSelectionDialog());
        layout.addView(btnSelectTitles);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("\u2139\uFE0F Your selections are saved automatically for next time.");
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(Color.GRAY);
        tvInfo.setPadding(0, 24, 0, 0);
        layout.addView(tvInfo);

        new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDD0D Filter Before Export")
                .setView(layout)
                .setPositiveButton("Export Filtered", (d, which) -> {
                    saveFilterPreferences();
                    exportFilteredCSV(dialogSkipEmpty, dialogExcludedPkgs, dialogExcludedTitles);
                })
                .setNeutralButton("Reset", (d, which) -> {
                    clearFilterPreferences();
                    Toast.makeText(this, "\u2705 Filter preferences reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getAppButtonText() {
        int count = dialogExcludedPkgs.size();
        return count == 0
                ? "\uD83D\uDCF1 Select Apps to Exclude"
                : String.format("\uD83D\uDCF1 Exclude Apps (%d selected)", count);
    }

    private String getTitleButtonText() {
        int count = dialogExcludedTitles.size();
        return count == 0
                ? "\uD83D\uDCAC Select Messages to Exclude"
                : String.format("\uD83D\uDCAC Exclude Messages (%d selected)", count);
    }

    // --- Sub-dialog: App multi-select (sorted by frequency, full list) ---
    private void showPackageSelectionDialog() {
        List<String> allPkgs = extractUniquePackagesByFrequency();
        if (allPkgs.isEmpty()) {
            Toast.makeText(this, "No apps found in log yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] displayNames = new String[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            String pkg = allPkgs.get(i);
            String appName = getAppName(pkg);
            displayNames[i] = appName.equals(pkg) ? pkg : appName + "\n" + pkg;
        }

        boolean[] checkedItems = new boolean[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            checkedItems[i] = dialogExcludedPkgs.contains(allPkgs.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Apps to Exclude (" + allPkgs.size() + ")")
                .setMultiChoiceItems(displayNames, checkedItems, (dialog, which, isChecked) ->
                        checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    dialogExcludedPkgs.clear();
                    for (int i = 0; i < allPkgs.size(); i++) {
                        if (checkedItems[i]) dialogExcludedPkgs.add(allPkgs.get(i));
                    }
                    if (btnSelectApps != null) btnSelectApps.setText(getAppButtonText());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Sub-dialog: Title multi-select (sorted by frequency) ---
    private void showTitleSelectionDialog() {
        List<String> allTitles = extractUniqueTitlesByFrequency();
        if (allTitles.isEmpty()) {
            Toast.makeText(this, "No messages found in log yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] displayNames = new String[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            String t = allTitles.get(i);
            displayNames[i] = t.length() > 100 ? t.substring(0, 97) + "..." : t;
        }

        boolean[] checkedItems = new boolean[allTitles.size()];
        for (int i = 0; i < allTitles.size(); i++) {
            checkedItems[i] = dialogExcludedTitles.contains(allTitles.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Messages to Exclude (" + allTitles.size() + ")")
                .setMultiChoiceItems(displayNames, checkedItems, (dialog, which, isChecked) ->
                        checkedItems[which] = isChecked)
                .setPositiveButton("OK", (dialog, which) -> {
                    dialogExcludedTitles.clear();
                    for (int i = 0; i < allTitles.size(); i++) {
                        if (checkedItems[i]) dialogExcludedTitles.add(allTitles.get(i));
                    }
                    if (btnSelectTitles != null) btnSelectTitles.setText(getTitleButtonText());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- SharedPreferences ---
    private void loadFilterPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        dialogSkipEmpty = prefs.getBoolean(PREF_SKIP_EMPTY, true);
        dialogExcludedPkgs = new HashSet<>(prefs.getStringSet(PREF_EXCLUDED_PKGS, Collections.emptySet()));
        dialogExcludedTitles = new HashSet<>(prefs.getStringSet(PREF_EXCLUDED_TITLES, Collections.emptySet()));
    }

    private void saveFilterPreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_SKIP_EMPTY, dialogSkipEmpty)
                .putStringSet(PREF_EXCLUDED_PKGS, dialogExcludedPkgs)
                .putStringSet(PREF_EXCLUDED_TITLES, dialogExcludedTitles)
                .apply();
    }

    private void clearFilterPreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        dialogSkipEmpty = true;
        dialogExcludedPkgs.clear();
        dialogExcludedTitles.clear();
    }

    // --- CSV extraction sorted by frequency (most frequent first) ---

    /**
     * Returns ALL unique packages sorted by occurrence count descending.
     * No cap — full list as requested.
     */
    private List<String> extractUniquePackagesByFrequency() {
        Map<String, Integer> freqMap = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; }
                String[] parts = parseCsvLine(line);
                if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                    String pkg = parts[1].trim();
                    Integer count = freqMap.get(pkg);
                    freqMap.put(pkg, count == null ? 1 : count + 1);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freqMap.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue()); // descending
            }
        });

        List<String> result = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            result.add(entry.getKey());
        }
        return result;
    }

    /**
     * Returns unique titles sorted by occurrence count descending.
     * Capped at 500 to keep the dialog usable.
     */
    private List<String> extractUniqueTitlesByFrequency() {
        Map<String, Integer> freqMap = new HashMap<>();
        File csvFile = new File(getFilesDir(), "notification_history.csv");
        if (!csvFile.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; }
                String[] parts = parseCsvLine(line);
                if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
                    String title = parts[2].trim();
                    Integer count = freqMap.get(title);
                    freqMap.put(title, count == null ? 1 : count + 1);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freqMap.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue()); // descending
            }
        });

        List<String> result = new ArrayList<>();
        int limit = Math.min(entries.size(), 2000);
        for (int i = 0; i < limit; i++) {
            result.add(entries.get(i).getKey());
        }
        return result;
    }

    // Robust CSV line parser
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null || line.isEmpty()) return new String[0];
        int i = 0, len = line.length();
        while (i < len) {
            if (line.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            sb.append('"'); i += 2;
                        } else {
                            i++; break;
                        }
                    } else {
                        sb.append(c); i++;
                    }
                }
                fields.add(sb.toString());
                if (i < len && line.charAt(i) == ',') i++;
            } else {
                int comma = line.indexOf(',', i);
                if (comma == -1) { fields.add(line.substring(i)); i = len; }
                else { fields.add(line.substring(i, comma)); i = comma + 1; }
            }
        }
        return fields.toArray(new String[0]);
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

    // --- Filtered export with UTF-8 BOM for Excel compatibility ---
    private void exportFilteredCSV(boolean skipEmpty, Set<String> excludePkgs, Set<String> excludeTitles) {
        File rawFile = new File(getFilesDir(), "notification_history.csv");
        if (!rawFile.exists() || rawFile.length() <= 40) {
            Toast.makeText(this, "No raw data to filter.", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> lowerPkgs = new HashSet<>();
        for (String p : excludePkgs) lowerPkgs.add(p.toLowerCase());

        Set<String> lowerTitles = new HashSet<>();
        for (String t : excludeTitles) lowerTitles.add(t.toLowerCase());

        File filteredFile = new File(getCacheDir(), "notification_history_filtered.csv");
        int kept = 0, skipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(rawFile)));
             OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(filteredFile))) {

            // Write UTF-8 BOM so Excel recognizes encoding correctly
            writer.write('\uFEFF');

            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) { writer.write(line + "\n"); isHeader = false; continue; }
                if (line.trim().isEmpty()) continue;

                String[] parts = parseCsvLine(line);
                if (parts.length < 4) { writer.write(line + "\n"); kept++; continue; }

                String pkg = parts[1].trim();
                String title = parts[2].trim();
                String text = parts[3].trim();

                boolean skip = false;
                if (skipEmpty && (text.isEmpty() || text.equalsIgnoreCase("N/A"))) skip = true;
                if (!skip && lowerPkgs.contains(pkg.toLowerCase())) skip = true;
                if (!skip && lowerTitles.contains(title.toLowerCase())) skip = true;

                if (skip) { skipped++; }
                else { writer.write(line + "\n"); kept++; }
            }
        } catch (Exception e) {
            Toast.makeText(this, "\u274C Filter failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            return;
        }

        if (kept == 0) {
            Toast.makeText(this, "\u26A0\uFE0F All rows filtered out.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", filteredFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Filtered CSV (" + kept + " rows)"));
            Toast.makeText(this, "\u2705 Kept: " + kept + " | Skipped: " + skipped, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "\u274C Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}