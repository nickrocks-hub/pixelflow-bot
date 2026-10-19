package com.aigame.subwaysurfers;

import android.content.Context;
import android.content.pm.PackageInfo;

/** App version for logs, without depending on the generated BuildConfig class. */
final class BuildConfigCompat {
    private BuildConfigCompat() {}

    static String version(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
