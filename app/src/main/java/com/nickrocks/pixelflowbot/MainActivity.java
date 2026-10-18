package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private TextView statusView;
    private EditText xField, yField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setTextSize(15);
        intro.setText("Setup:\n"
                + "1. Turn on the tap service (Accessibility). A floating panel appears.\n"
                + "2. Start screen capture and allow it (choose \"Entire screen\").\n"
                + "3. Open Pixel Flow and use the panel: Test tap, Save frame, STOP.\n\n"
                + "STOP ends capture, cancels taps and turns the tap service off.");
        root.addView(intro);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setPadding(0, dp(12), 0, dp(12));
        root.addView(statusView);

        root.addView(button("1. Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(button("2. Start screen capture", v -> requestCapture()));

        TextView tapLabel = new TextView(this);
        tapLabel.setText("Test tap point in pixels (blank = screen centre):");
        tapLabel.setPadding(0, dp(16), 0, 0);
        root.addView(tapLabel);

        LinearLayout row = new LinearLayout(this);
        xField = numberField("X");
        yField = numberField("Y");
        row.addView(xField);
        row.addView(yField);
        root.addView(row);

        SharedPreferences p = getSharedPreferences(TapService.PREFS, MODE_PRIVATE);
        if (p.contains(TapService.KEY_X)) {
            xField.setText(String.valueOf((int) p.getFloat(TapService.KEY_X, 0)));
            yField.setText(String.valueOf((int) p.getFloat(TapService.KEY_Y, 0)));
        }
        root.addView(button("Save tap point", v -> saveTapPoint()));

        Button stop = button("STOP everything", v -> {
            CaptureService.stop(this);
            TapService.stopEverything();
            refresh();
        });
        stop.setBackgroundColor(0xFFD93025);
        stop.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) stop.getLayoutParams();
        lp.topMargin = dp(24);
        root.addView(stop);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        statusView.setText("Tap service: " + (TapService.isRunning() ? "ON" : "off")
                + "\nScreen capture: " + (CaptureService.isRunning() ? "ON" : "off"));
    }

    private void requestCapture() {
        if (CaptureService.isRunning()) {
            Toast.makeText(this, "Capture already running", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        statusView.postDelayed(this::refresh, 800);
    }

    private void saveTapPoint() {
        SharedPreferences.Editor e = getSharedPreferences(TapService.PREFS, MODE_PRIVATE).edit();
        String xs = xField.getText().toString().trim();
        String ys = yField.getText().toString().trim();
        if (xs.isEmpty() || ys.isEmpty()) {
            e.remove(TapService.KEY_X).remove(TapService.KEY_Y).apply();
            Toast.makeText(this, "Using screen centre", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            e.putFloat(TapService.KEY_X, Float.parseFloat(xs))
             .putFloat(TapService.KEY_Y, Float.parseFloat(ys)).apply();
            Toast.makeText(this, "Tap point saved", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Enter whole numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private EditText numberField(String hint) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return f;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
