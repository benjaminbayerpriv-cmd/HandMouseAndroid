package com.doubleb.handmouse;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class HandMouseAccessibilityService extends AccessibilityService {
    private static volatile HandMouseAccessibilityService instance;
    private static final String CHANNEL_ID = "hand_mouse_tracking";
    private static final int NOTIFICATION_ID = 42;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorParams;
    private WebView trackerWebView;
    private WindowManager.LayoutParams trackerParams;
    private LocalAssetServer assetServer;
    private volatile boolean tracking;

    private int screenW;
    private int screenH;
    private float cursorX;
    private float cursorY;
    private String gesture = "none";

    // Continued Accessibility gesture state.
    private GestureDescription.StrokeDescription stroke;
    private boolean strokeActive;
    private boolean strokeHeld;
    private boolean segmentRunning;
    private float strokeEndX;
    private float strokeEndY;
    private float desiredX;
    private float desiredY;
    private String strokeMode = "none";
    private String pendingMode = "none";

    public static HandMouseAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createCursorOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopTracking();
        removeCursor();
        instance = null;
        return super.onUnbind(intent);
    }

    public boolean isTracking() {
        return tracking;
    }

    public void startTracking() {
        main.post(() -> {
            if (tracking) return;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            try {
                startAsForeground();
                assetServer = new LocalAssetServer(this);
                assetServer.start();
                createTrackerWebView();
                tracking = true;
                if (cursor != null) cursor.setVisibility(android.view.View.VISIBLE);
            } catch (Exception e) {
                e.printStackTrace();
                stopTracking();
            }
        });
    }

    public void stopTracking() {
        main.post(() -> {
            tracking = false;
            endStroke();
            if (trackerWebView != null) {
                try { wm.removeView(trackerWebView); } catch (Exception ignored) {}
                trackerWebView.stopLoading();
                trackerWebView.destroy();
                trackerWebView = null;
            }
            if (assetServer != null) {
                assetServer.stop();
                assetServer = null;
            }
            if (cursor != null) {
                cursor.setMode("none");
                cursor.setVisibility(android.view.View.GONE);
            }
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        });
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Hand Mouse Tracking", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hand Mouse aktiv")
                .setContentText("Frontkamera erkennt deine Handgesten")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    private void createCursorOverlay() {
        if (cursor != null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        cursorX = screenW / 2f;
        cursorY = screenH / 2f;

        cursor = new CursorView(this);
        int size = dp(38);
        cursorParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
        cursorParams.x = Math.round(cursorX);
        cursorParams.y = Math.round(cursorY);
        wm.addView(cursor, cursorParams);
        cursor.setVisibility(android.view.View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void createTrackerWebView() {
        trackerWebView = new WebView(this);
        WebSettings s = trackerWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        trackerWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        trackerWebView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        trackerWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });

        trackerParams = new WindowManager.LayoutParams(
                dp(4), dp(4),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        trackerParams.gravity = Gravity.TOP | Gravity.LEFT;
        trackerParams.x = 0;
        trackerParams.y = 0;
        trackerParams.alpha = 0.02f;
        wm.addView(trackerWebView, trackerParams);
        trackerWebView.loadUrl("http://127.0.0.1:" + LocalAssetServer.PORT + "/hand-skeleton.html");
    }

    private class JsBridge {
        @JavascriptInterface
        public void onFrame(double x, double y, String g) {
            main.post(() -> handleFrame((float)x, (float)y, g));
        }

        @JavascriptInterface
        public void onStatus(String text) {
            android.util.Log.d("HandMouse", text == null ? "" : text);
        }
    }

    private void handleFrame(float nx, float ny, String g) {
        if (!tracking) return;
        if (g == null) g = "none";
        nx = clamp(nx, 0f, 1f);
        ny = clamp(ny, 0f, 1f);

        if (!"fist".equals(g)) {
            cursorX = nx * screenW;
            cursorY = ny * screenH;
            desiredX = cursorX;
            desiredY = cursorY;
            moveCursorOverlay();
        }

        cursor.setMode(g);

        // Fist freezes pointer and safely releases any touch currently held.
        if ("fist".equals(g) || "none".equals(g)) {
            if (!"none".equals(gesture)) endStroke();
            gesture = g;
            return;
        }

        if (!g.equals(gesture)) {
            if (!"none".equals(gesture) && !"fist".equals(gesture)) {
                pendingMode = g;
                endStroke();
            } else {
                beginStroke(g);
            }
            gesture = g;
        } else if (strokeActive) {
            desiredX = cursorX;
            desiredY = cursorY;
        }
    }

    private void moveCursorOverlay() {
        if (cursor == null || cursorParams == null) return;
        int hotX = dp(3);
        int hotY = dp(2);
        cursorParams.x = Math.round(cursorX) - hotX;
        cursorParams.y = Math.round(cursorY) - hotY;
        try { wm.updateViewLayout(cursor, cursorParams); } catch (Exception ignored) {}
    }

    private void beginStroke(String mode) {
        if (strokeActive || segmentRunning) {
            pendingMode = mode;
            return;
        }
        strokeMode = mode;
        strokeHeld = true;
        strokeActive = true;
        strokeEndX = desiredX = cursorX;
        strokeEndY = desiredY = cursorY;

        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        long firstDuration = "right".equals(mode) ? 550L : 75L;
        stroke = new GestureDescription.StrokeDescription(p, 0, firstDuration, true);
        dispatchSegment(stroke);
    }

    private void endStroke() {
        if (!strokeActive) {
            strokeHeld = false;
            return;
        }
        strokeHeld = false;
        // Current segment must finish before a non-continuing segment can be dispatched.
        if (!segmentRunning) dispatchNextSegment();
    }

    private void dispatchNextSegment() {
        if (!strokeActive || stroke == null) return;
        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        p.lineTo(desiredX, desiredY);
        boolean keep = strokeHeld;
        long duration = keep ? 55L : 1L;
        try {
            stroke = stroke.continueStroke(p, 0, duration, keep);
            strokeEndX = desiredX;
            strokeEndY = desiredY;
            dispatchSegment(stroke);
        } catch (Exception e) {
            clearStroke();
        }
    }

    private void dispatchSegment(GestureDescription.StrokeDescription part) {
        GestureDescription gestureDescription = new GestureDescription.Builder().addStroke(part).build();
        segmentRunning = true;
        boolean accepted = dispatchGesture(gestureDescription, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                main.post(() -> {
                    segmentRunning = false;
                    if (!strokeActive) return;
                    if (!strokeHeld && !stroke.willContinue()) {
                        clearStroke();
                        startPendingIfAny();
                    } else {
                        dispatchNextSegment();
                    }
                });
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                main.post(() -> {
                    segmentRunning = false;
                    clearStroke();
                    startPendingIfAny();
                });
            }
        }, null);
        if (!accepted) {
            segmentRunning = false;
            clearStroke();
            startPendingIfAny();
        }
    }

    private void clearStroke() {
        stroke = null;
        strokeActive = false;
        strokeHeld = false;
        segmentRunning = false;
        strokeMode = "none";
    }

    private void startPendingIfAny() {
        String p = pendingMode;
        pendingMode = "none";
        if (!"none".equals(p) && !"fist".equals(p) && p.equals(gesture)) beginStroke(p);
    }

    private void removeCursor() {
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) {}
            cursor = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
