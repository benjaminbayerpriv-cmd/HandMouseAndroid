package com.doubleb.handmouse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestCameraIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(24));
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Hand Mouse");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full(dp(70)));

        TextView desc = new TextView(this);
        desc.setText("Handtracking → System-Cursor → echte Android-Taps & Drags\n\n🤏 Daumen + Zeigefinger: Linksklick / Drag\n✌ Daumen + Mittelfinger ohne Zeige: Long-Press / Drag\n✊ Faust: Cursor einfrieren");
        desc.setTextColor(0xffc7c7c7);
        desc.setTextSize(17);
        desc.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(desc, full(dp(180)));

        status = new TextView(this);
        status.setTextColor(0xffffd54f);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, full(dp(90)));

        Button camera = button("1. Kamera erlauben");
        camera.setOnClickListener(v -> requestCameraIfNeeded());
        root.addView(camera, full(dp(58)));

        Button accessibility = button("2. Bedienungshilfe aktivieren");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, withTop(dp(58), dp(12)));

        Button start = button("3. Handsteuerung starten");
        start.setOnClickListener(v -> startTracking());
        root.addView(start, withTop(dp(58), dp(12)));

        Button stop = button("Tracking stoppen");
        stop.setOnClickListener(v -> {
            HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopTracking();
                Toast.makeText(this, "Tracking gestoppt", Toast.LENGTH_SHORT).show();
            }
            updateStatus();
        });
        root.addView(stop, withTop(dp(58), dp(12)));

        TextView note = new TextView(this);
        note.setText("Hinweis: Android hat keinen normalen Touch-Rechtsklick. Die Mittelfinger-Geste wird deshalb als Long-Press (Android-Kontextmenü) ausgeführt. Während des Trackings zeigt Android den Kamera-Datenschutzindikator.");
        note.setTextColor(0xff808080);
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(note, withTop(dp(115), dp(24)));

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
        Toast.makeText(this, "Handsteuerung läuft – du kannst jetzt Home drücken", Toast.LENGTH_LONG).show();
        updateStatus();
    }

    private void updateStatus() {
        HandMouseAccessibilityService svc = HandMouseAccessibilityService.getInstance();
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        String s = "Kamera: " + (camera ? "✓" : "✗") + "\nBedienungshilfe: " + (svc != null ? "✓" : "✗");
        if (svc != null) s += "\nTracking: " + (svc.isTracking() ? "AKTIV" : "aus");
        status.setText(s);
    }
}
