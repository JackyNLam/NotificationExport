package com.example.notificationexport;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Filter rules shared by manual export, auto-export and the filter dialog.
 * Rules are persisted so the scheduled export always uses the same selection.
 */
public class FilterConfig {

    public static final String PREFS_NAME = "filter_prefs";

    private static final String K_SKIP_EMPTY = "skip_empty";
    private static final String K_EXCLUDED_PKGS = "excluded_pkgs";
    private static final String K_EXCLUDED_TITLES = "excluded_titles";
    private static final String K_INCLUDED_PKGS = "included_pkgs";
    private static final String K_INCLUDED_TITLES = "included_titles";
    private static final String K_INCLUDE_ONLY_PKGS = "include_only_pkgs";
    private static final String K_INCLUDE_ONLY_TITLES = "include_only_titles";

    public boolean skipEmpty = true;
    public boolean includeOnlyPkgs = false;
    public boolean includeOnlyTitles = false;

    public Set<String> excludedPkgs = new HashSet<>();
    public Set<String> excludedTitles = new HashSet<>();
    public Set<String> includedPkgs = new HashSet<>();
    public Set<String> includedTitles = new HashSet<>();

    public static FilterConfig load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        FilterConfig c = new FilterConfig();
        c.skipEmpty = p.getBoolean(K_SKIP_EMPTY, true);
        c.includeOnlyPkgs = p.getBoolean(K_INCLUDE_ONLY_PKGS, false);
        c.includeOnlyTitles = p.getBoolean(K_INCLUDE_ONLY_TITLES, false);
        c.excludedPkgs = new HashSet<>(p.getStringSet(K_EXCLUDED_PKGS, Collections.<String>emptySet()));
        c.excludedTitles = new HashSet<>(p.getStringSet(K_EXCLUDED_TITLES, Collections.<String>emptySet()));
        c.includedPkgs = new HashSet<>(p.getStringSet(K_INCLUDED_PKGS, Collections.<String>emptySet()));
        c.includedTitles = new HashSet<>(p.getStringSet(K_INCLUDED_TITLES, Collections.<String>emptySet()));
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(K_SKIP_EMPTY, skipEmpty)
                .putBoolean(K_INCLUDE_ONLY_PKGS, includeOnlyPkgs)
                .putBoolean(K_INCLUDE_ONLY_TITLES, includeOnlyTitles)
                .putStringSet(K_EXCLUDED_PKGS, excludedPkgs)
                .putStringSet(K_EXCLUDED_TITLES, excludedTitles)
                .putStringSet(K_INCLUDED_PKGS, includedPkgs)
                .putStringSet(K_INCLUDED_TITLES, includedTitles)
                .apply();
    }

    public static void reset(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** True when this row should be exported. */
    public boolean matches(String pkg, String title, String text) {
        if (skipEmpty && (text.isEmpty() || text.equalsIgnoreCase("N/A"))) return false;

        String p = pkg.toLowerCase(Locale.ROOT);
        String t = title.toLowerCase(Locale.ROOT);

        if (includeOnlyPkgs && !includedPkgs.isEmpty()) {
            if (!containsIgnoreCase(includedPkgs, p)) return false;
        } else if (containsIgnoreCase(excludedPkgs, p)) {
            return false;
        }

        if (includeOnlyTitles && !includedTitles.isEmpty()) {
            if (!containsIgnoreCase(includedTitles, t)) return false;
        } else if (containsIgnoreCase(excludedTitles, t)) {
            return false;
        }

        return true;
    }

    private static boolean containsIgnoreCase(Set<String> set, String lowerValue) {
        for (String s : set) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(lowerValue)) return true;
        }
        return false;
    }
}