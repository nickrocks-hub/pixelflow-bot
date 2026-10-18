package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.core.GameProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * "Add a game": pick an installed app, name it, say whether it's turn-based or real-time.
 * The new profile starts in CALIBRATING: the app is honest that nothing understands it yet.
 */
final class AddGame {
    private AddGame() { }

    private static final class App {
        String label, pkg;
    }

    static void start(Activity a) {
        Handler h = new Handler(Looper.getMainLooper());
        Toast.makeText(a, "Loading your apps…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<App> apps = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(a.getPackageName())) continue;
                App app = new App();
                app.pkg = pkg;
                app.label = String.valueOf(ri.loadLabel(pm));
                boolean dup = false;
                for (App x : apps) if (x.pkg.equals(pkg)) dup = true;
                if (!dup) apps.add(app);
            }
            apps.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
            h.post(() -> pick(a, apps));
        }, "add-game").start();
    }

    private static void pick(Activity a, List<App> apps) {
        if (a.isFinishing()) return;
        if (apps.isEmpty()) { Toast.makeText(a, "No apps found.", Toast.LENGTH_LONG).show(); return; }
        String[] items = new String[apps.size()];
        for (int i = 0; i < items.length; i++) items[i] = apps.get(i).label;
        new AlertDialog.Builder(a).setTitle("Which game?").setItems(items, (d, w) -> details(a, apps.get(w)))
                .setNegativeButton("Cancel", null).show();
    }

    private static void details(Activity a, App app) {
        AgentPlatform pl = AgentPlatform.get(a);
        for (GameProfile p : pl.profiles.list()) {
            if (app.pkg.equals(p.packageName)) {
                Toast.makeText(a, p.name + " is already in the library.", Toast.LENGTH_LONG).show();
                a.startActivity(new Intent(a, ProfileActivity.class).putExtra(ProfileActivity.EXTRA_ID, p.id));
                return;
            }
        }
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        EditText name = new EditText(a);
        name.setText(app.label);
        name.setHint("Name");
        box.addView(name);
        TextView q = new TextView(a);
        q.setText("Is it turn-based (waits for you) or real-time (things move on their own)? This decides how fast the agent "
                + "has to react; you can change it later.");
        q.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(q);
        String[] paces = {"Turn-based", "Real-time", "Not sure"};
        final int[] choice = {2};
        new AlertDialog.Builder(a).setTitle("Add " + app.label).setView(box)
                .setSingleChoiceItems(paces, 2, (dd, w) -> choice[0] = w)
                .setPositiveButton("Add", (dd, w) -> {
                    GameProfile p = new GameProfile();
                    String n = name.getText().toString().trim();
                    p.name = n.isEmpty() ? app.label : n;
                    p.id = pl.profiles.freeId(p.name);
                    p.kind = GameProfile.Kind.EXTERNAL_APP;
                    p.packageName = app.pkg;
                    p.support = GameProfile.Support.CALIBRATING;
                    p.pace = choice[0] == 0 ? GameProfile.Pace.TURN_BASED : choice[0] == 1 ? GameProfile.Pace.REAL_TIME : GameProfile.Pace.UNKNOWN;
                    p.notes = "Added by you. No adapter yet.";
                    try {
                        GameProfile saved = pl.profiles.save(p);
                        a.startActivity(new Intent(a, ProfileActivity.class).putExtra(ProfileActivity.EXTRA_ID, saved.id));
                    } catch (Exception e) {
                        Toast.makeText(a, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
