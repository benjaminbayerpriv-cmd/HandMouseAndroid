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

    private final Runnable loop = new Runnable() {
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
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(loop);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(24));
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Hand Mouse · v8");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full(dp(62)));

        TextView desc = new TextView(this);
        desc.setText("Foreground-Kamera-Service + Accessibility-WebEngine\nlocalhost → WebView → JS → MediaPipe → Kamera");
        desc.setTextColor(0xffc7c7c7);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        root.addView(desc, full(dp(90)));

        status = new TextView(this);
        status.setTextColor(0xffffd54f);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, full(dp(180)));

        Button camera = button("1. Kamera erlauben");
        camera.setOnClickListener(v -> requestCameraIfNeeded());
        root.addView(camera, full(dp(58)));

        Button accessibility = button("2. Bedienungshilfe aktivieren");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, withTop(dp(58), dp(10)));

        Button start = button("3. Handtracking starten");
        start.setOnClickListener(v -> startTracking());
        root.addView(start, withTop(dp(58), dp(10)));

        Button stop = button("Tracking stoppen");
        stop.setOnClickListener(v -> stopTracking());
        root.addView(stop, withTop(dp(58), dp(10)));

        setContentView(root);
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraIfNeeded();
            Toast.makeText(this, "Erst Kamera erlauben", Toast.LENGTH_SHORT).show();
            return;
        }

        if (HandMouseAccessibilityService.getInstance() == null) {
            Toast.makeText(this, "Aktiviere zuerst die Bedienungshilfe", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        Intent i = new Intent(this, CameraWebService.class);
        i.setAction(CameraWebService.ACTION_START);
        startForegroundService(i);

        Toast.makeText(this, "Kamera-Service gestartet", Toast.LENGTH_SHORT).show();
    }

    private void stopTracking() {
        Intent i = new Intent(this, CameraWebService.class);
        i.setAction(CameraWebService.ACTION_STOP);
        startService(i);
    }

    private void updateStatus() {
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();

        StringBuilder s = new StringBuilder();
        s.append("Kamera-Recht: ").append(cam ? "✓" : "✗");
        s.append("\nBedienungshilfe: ").append(svc != null ? "✓" : "✗");
        s.append("\nForeground-Service: ").append(CameraWebService.isRunning() ? "AKTIV" : "aus");

        if (svc != null) {
            s.append("\nWebEngine: ").append(svc.isTracking() ? "AKTIV" : "aus");
            s.append("\nFPS: ").append(String.format(java.util.Locale.US, "%.1f", svc.getLastFps()));
            s.append("\n\n").append(svc.getDiagnosticStatus());
        }

        status.setText(s.toString());
    }

    private void requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams full(int h) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    private LinearLayout.LayoutParams withTop(int h, int top) {
        LinearLayout.LayoutParams p = full(h);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
