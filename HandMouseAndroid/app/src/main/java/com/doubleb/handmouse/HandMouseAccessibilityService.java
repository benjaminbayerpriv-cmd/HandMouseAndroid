package com.doubleb.handmouse;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
import android.webkit.WebViewClient;

public class HandMouseAccessibilityService extends AccessibilityService {
    private static volatile HandMouseAccessibilityService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorParams;

    private WebView trackerWebView;
    private LocalAssetServer assetServer;

    private int screenW;
    private int screenH;
    private float cursorX;
    private float cursorY;
    private String gesture = "none";

    private volatile boolean webEngineRunning = false;
    private volatile String diagnosticStatus = "Bereit";
    private volatile double fps = 0.0;

    private GestureDescription.StrokeDescription stroke;
    private boolean strokeActive;
    private boolean strokeHeld;
    private boolean segmentRunning;
    private float strokeEndX;
    private float strokeEndY;
    private float desiredX;
    private float desiredY;
    private String pendingMode = "none";

    public static HandMouseAccessibilityService getInstance() { return instance; }
    public boolean isTracking() { return webEngineRunning; }
    public String getDiagnosticStatus() { return diagnosticStatus; }
    public double getLastFps() { return fps; }

    private void setStatus(String s) {
        diagnosticStatus = s == null ? "" : s;
        android.util.Log.d("HandMouse", diagnosticStatus);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createCursorOverlay();
        setStatus("Bedienungshilfe aktiv · Cursor bereit");

        if (CameraWebService.isRunning()) {
            startWebEngine();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopWebEngine();
        removeCursor();
        instance = null;
        return super.onUnbind(intent);
    }

    public void startWebEngine() {
        main.post(() -> {
            if (webEngineRunning) return;

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setStatus("KAMERA FEHLER · Android-Berechtigung fehlt");
                return;
            }

            try {
                if (cursor != null) cursor.setVisibility(android.view.View.VISIBLE);

                setStatus("Starte localhost 127.0.0.1:" + LocalAssetServer.PORT + "…");
                assetServer = new LocalAssetServer(this);
                assetServer.start();
                setStatus("Server: OK · starte WebView…");

                createTrackerWebView();
                webEngineRunning = true;
            } catch (Throwable t) {
                setStatus("START FEHLER · " + t.getClass().getSimpleName() + " · " + t.getMessage());
                cleanupWebEngine(false);
            }
        });
    }

    public void stopWebEngine() {
        main.post(() -> cleanupWebEngine(true));
    }

    private void cleanupWebEngine(boolean showStopped) {
        webEngineRunning = false;
        endStroke();

        if (trackerWebView != null) {
            try { wm.removeView(trackerWebView); } catch (Throwable ignored) {}
            try { trackerWebView.stopLoading(); } catch (Throwable ignored) {}
            try { trackerWebView.destroy(); } catch (Throwable ignored) {}
            trackerWebView = null;
        }

        if (assetServer != null) {
            try { assetServer.stop(); } catch (Throwable ignored) {}
            assetServer = null;
        }

        fps = 0.0;
        if (cursor != null) {
            cursor.setMode("none");
            cursor.setVisibility(android.view.View.VISIBLE);
        }

        if (showStopped) setStatus("Tracking aus · Cursor bleibt sichtbar");
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
        cursor.setVisibility(android.view.View.VISIBLE);
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
        trackerWebView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        trackerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setStatus("WebView lädt…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setStatus("WebView: OK · HTML geladen");
            }

            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    setStatus("WEBVIEW FEHLER · " + error.getErrorCode() + " · " + error.getDescription());
                }
            }
        });

        trackerWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> {
                    setStatus("WebView fragt Kamera an…");
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                        setStatus("KAMERA FEHLER · WebView-Kamerarecht abgelehnt");
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                android.util.Log.d("HandMouseJS", msg.message());
                return true;
            }
        });

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(8), dp(8),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 0;
        p.y = 0;
        p.alpha = 0.03f;

        wm.addView(trackerWebView, p);
        trackerWebView.loadUrl("http://127.0.0.1:" + LocalAssetServer.PORT + "/hand-skeleton.html");
    }

    private class Bridge {
        @JavascriptInterface
        public void onFrame(double x, double y, String g) {
            receiveTrackingFrame((float)x, (float)y, g);
        }

        @JavascriptInterface
        public void onStatus(String text) {
            setStatus(text);
        }

        @JavascriptInterface
        public void onFps(double value) {
            fps = value;
            if (value > 0) {
                setStatus(String.format(java.util.Locale.US, "TRACKING OK · %.1f FPS", value));
            }
        }
    }

    public void receiveTrackingFrame(float nx, float ny, String g) {
        main.post(() -> handleFrame(nx, ny, g));
    }

    private void handleFrame(float nx, float ny, String g) {
        if (!webEngineRunning) return;
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

        if (cursor != null) cursor.setMode(g);

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
        cursorParams.x = Math.round(cursorX) - dp(3);
        cursorParams.y = Math.round(cursorY) - dp(2);
        try { wm.updateViewLayout(cursor, cursorParams); } catch (Throwable ignored) {}
    }

    private void beginStroke(String mode) {
        if (strokeActive || segmentRunning) {
            pendingMode = mode;
            return;
        }

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
        } catch (Throwable t) {
            clearStroke();
        }
    }

    private void dispatchSegment(GestureDescription.StrokeDescription part) {
        GestureDescription gd = new GestureDescription.Builder().addStroke(part).build();
        segmentRunning = true;

        boolean accepted = dispatchGesture(gd, new GestureResultCallback() {
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
    }

    private void startPendingIfAny() {
        String p = pendingMode;
        pendingMode = "none";
        if (!"none".equals(p) && !"fist".equals(p) && p.equals(gesture)) {
            beginStroke(p);
        }
    }

    private void removeCursor() {
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Throwable ignored) {}
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
