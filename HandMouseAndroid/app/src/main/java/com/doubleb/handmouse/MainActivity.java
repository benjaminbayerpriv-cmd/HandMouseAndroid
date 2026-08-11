package com.doubleb.handmouse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;

    private final Runnable statusLoop = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestCameraIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusLoop);
        handler.post(statusLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusLoop);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(24));
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Hand Mouse · Diagnose v7");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full(dp(62)));

        TextView desc = new TextView(this);
        desc.setText("Diese Version zeigt genau, wo der Start hängt:\\nlocalhost → WebView → JS → MediaPipe → Kamera → Tracking-FPS");
        desc.setTextColor(0xffc7c7c7);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(desc, full(dp(100)));

        status = new TextView(this);
        status.setTextColor(0xffffd54f);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, full(dp(170)));

        Button camera = button("1. Kamera erlauben");
        camera.setOnClickListener(v -> requestCameraIfNeeded());
        root.addView(camera, full(dp(58)));

        Button accessibility = button("2. Bedienungshilfe aktivieren");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, withTop(dp(58), dp(10)));

        Button start = button("3. Diagnose + Handtracking starten");
        start.setOnClickListener(v -> startTracking());
        root.addView(start, withTop(dp(58), dp(10)));

        Button stop = button("Tracking stoppen");
        stop.setOnClickListener(v -> {
            HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
            if (svc != null) svc.stopTracking();
            updateStatus();
        });
        root.addView(stop, withTop(dp(58), dp(10)));

        TextView note = new TextView(this);
        note.setText("Wichtig: Der Cursor sollte schon nach Aktivieren der Bedienungshilfe sichtbar sein. Wenn die Kamera nicht startet, lies die gelbe Diagnosezeile ab.");
        note.setTextColor(0xff909090);
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(note, withTop(dp(100), dp(18)));

        setContentView(root);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams full(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams withTop(int height, int top) {
        LinearLayout.LayoutParams p = full(height);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            Toast.makeText(this, "Kamera-Berechtigung ist erteilt", Toast.LENGTH_SHORT).show();
        }
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraIfNeeded();
            Toast.makeText(this, "Erst Kamera erlauben", Toast.LENGTH_SHORT).show();
            return;
        }
        HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
        if (svc == null) {
            Toast.makeText(this, "Aktiviere zuerst 'Hand Mouse Steuerung' unter Bedienungshilfen", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        svc.startTracking();
        Toast.makeText(this, "Diagnose gestartet", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        StringBuilder s = new StringBuilder();
        s.append("Android Kamera-Recht: ").append(camera ? "✓" : "✗");
        s.append("\\nBedienungshilfe: ").append(svc != null ? "✓" : "✗");

        if (svc != null) {
            s.append("\\nTracking: ").append(svc.isTracking() ? "AKTIV" : "aus");
            s.append("\\nFPS: ").append(String.format(java.util.Locale.US, "%.1f", svc.getLastFps()));
            s.append("\\n\\n").append(svc.getDiagnosticStatus());
        } else {
            s.append("\\n\\nAktiviere zuerst die Bedienungshilfe.");
        }

        status.setText(s.toString());
    }
}
