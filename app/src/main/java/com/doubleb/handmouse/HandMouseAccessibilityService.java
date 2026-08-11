package com.doubleb.handmouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class HandMouseAccessibilityService extends AccessibilityService implements HandTrackingService.Listener {
    private static volatile HandMouseAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams params;
    private int screenW, screenH;
    private float cursorX, cursorY;

    // Camera active zone -> whole screen. This lets you hit screen edges without moving your hand out of frame.
    private static final float ACTIVE_LEFT = 0.15f;
    private static final float ACTIVE_RIGHT = 0.85f;
    private static final float ACTIVE_TOP = 0.12f;
    private static final float ACTIVE_BOTTOM = 0.84f;

    // Input state. Tap occurs only on release. Drag begins only after intentional hold.
    private String activeMode = "none";
    private long pinchStartMs;
    private boolean dragActive;
    private static final long LEFT_DRAG_HOLD_MS = 430L;
    private static final long RIGHT_DRAG_HOLD_MS = 620L;
    private static final long RELEASE_GRACE_MS = 45L;

    // Continued Android drag.
    private GestureDescription.StrokeDescription stroke;
    private boolean segmentRunning;
    private float strokeEndX, strokeEndY;
    private float desiredDragX, desiredDragY;

    // Button aim assist cache.
    private long lastAssistScanMs;
    private float assistX, assistY;
    private boolean assistValid;
    private static final long ASSIST_SCAN_INTERVAL_MS = 80L;
    private static final float ASSIST_RADIUS_DP = 86f;
    private static final float ASSIST_STRONG_RADIUS_DP = 32f;

    private final Runnable releaseRunnable = () -> finishPinch(false);

    public static HandMouseAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        createCursor();
        HandTrackingService.setListener(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // Invalidate cached target when UI changes.
        assistValid = false;
    }

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent i) {
        main.removeCallbacks(releaseRunnable);
        cancelGestureImmediately();
        removeCursor();
        HandTrackingService.setListener(null);
        instance = null;
        return super.onUnbind(i);
    }

    private void createCursor() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        cursorX = screenW / 2f;
        cursorY = screenH / 2f;

        cursor = new CursorView(this);
        int size = dp(38);
        params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = Math.round(cursorX) - dp(3);
        params.y = Math.round(cursorY) - dp(2);
        wm.addView(cursor, params);
        cursor.setVisibility(android.view.View.VISIBLE);
    }

    private void moveCursorOverlay() {
        if (params == null || cursor == null || !cursor.isAttachedToWindow()) return;
        params.x = Math.round(cursorX) - dp(3);
        params.y = Math.round(cursorY) - dp(2);
        try { wm.updateViewLayout(cursor, params); } catch (Exception ignored) {}
    }

    @Override public void onTrackingFrame(float x, float y, String gesture) {
        main.post(() -> handleFrame(x, y, gesture));
    }

    @Override public void onTrackingStatus(String s) {}

    private void handleFrame(float nx, float ny, String g) {
        if (g == null) g = "none";

        // Higher sensitivity: 70% of camera width/72% height maps to 100% of display.
        float mappedX = remap(nx, ACTIVE_LEFT, ACTIVE_RIGHT) * screenW;
        float mappedY = remap(ny, ACTIVE_TOP, ACTIVE_BOTTOM) * screenH;

        if (!"fist".equals(g)) {
            cursorX = mappedX;
            cursorY = mappedY;

            // Aim assist only when not dragging. It magnetically attracts nearby clickable elements.
            if (!dragActive) applyAimAssist();

            moveCursorOverlay();
            if (dragActive) {
                desiredDragX = cursorX;
                desiredDragY = cursorY;
            }
        }

        cursor.setMode(g);

        if ("fist".equals(g)) {
            main.removeCallbacks(releaseRunnable);
            finishPinch(true);
            return;
        }

        if ("left".equals(g) || "right".equals(g)) {
            main.removeCallbacks(releaseRunnable);
            if ("none".equals(activeMode)) {
                startPinch(g);
            } else if (!g.equals(activeMode)) {
                finishPinch(true);
                startPinch(g);
            }
            maybeStartHeldDrag();
            return;
        }

        if (!"none".equals(activeMode)) {
            main.removeCallbacks(releaseRunnable);
            main.postDelayed(releaseRunnable, RELEASE_GRACE_MS);
        }
    }

    private float remap(float v, float lo, float hi) {
        float t = (v - lo) / Math.max(0.001f, hi - lo);
        return Math.max(0f, Math.min(1f, t));
    }

    private void startPinch(String mode) {
        activeMode = mode;
        pinchStartMs = SystemClock.uptimeMillis();
        dragActive = false;
        clearStrokeState();
    }

    private void maybeStartHeldDrag() {
        if ("none".equals(activeMode) || dragActive || segmentRunning) return;
        long held = SystemClock.uptimeMillis() - pinchStartMs;
        long threshold = "right".equals(activeMode) ? RIGHT_DRAG_HOLD_MS : LEFT_DRAG_HOLD_MS;
        if (held >= threshold) beginDrag(activeMode);
    }

    private void finishPinch(boolean cancelTap) {
        main.removeCallbacks(releaseRunnable);
        if ("none".equals(activeMode)) return;
        String mode = activeMode;
        activeMode = "none";

        if (dragActive) {
            endDrag();
            return;
        }

        if (!cancelTap) {
            if ("left".equals(mode)) {
                // Buttons are more reliable when clicked as accessibility nodes; fallback to physical tap.
                if (!clickNearestClickable(cursorX, cursorY, dpFloat(64f))) dispatchTap(cursorX, cursorY);
            } else if ("right".equals(mode)) {
                dispatchLongPress(cursorX, cursorY);
            }
        }
        clearStrokeState();
    }

    private void dispatchTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 42, false);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void dispatchLongPress(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 520, false);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void beginDrag(String mode) {
        if (dragActive || segmentRunning) return;
        dragActive = true;
        strokeEndX = desiredDragX = cursorX;
        strokeEndY = desiredDragY = cursorY;

        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        long initialHold = "right".equals(mode) ? 510L : 32L;
        stroke = new GestureDescription.StrokeDescription(p, 0, initialHold, true);
        dispatchDragSegment(stroke);
    }

    private void endDrag() {
        if (!dragActive) { clearStrokeState(); return; }
        dragActive = false;
        if (!segmentRunning) dispatchFinalDragSegment();
    }

    private void dispatchNextDragSegment() {
        if (stroke == null) return;
        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        p.lineTo(desiredDragX, desiredDragY);
        try {
            stroke = stroke.continueStroke(p, 0, 28L, true);
            strokeEndX = desiredDragX;
            strokeEndY = desiredDragY;
            dispatchDragSegment(stroke);
        } catch (Exception e) {
            clearStrokeState();
        }
    }

    private void dispatchFinalDragSegment() {
        if (stroke == null) { clearStrokeState(); return; }
        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        p.lineTo(desiredDragX, desiredDragY);
        try {
            stroke = stroke.continueStroke(p, 0, 8L, false);
            strokeEndX = desiredDragX;
            strokeEndY = desiredDragY;
            dispatchDragSegment(stroke);
        } catch (Exception e) {
            clearStrokeState();
        }
    }

    private void dispatchDragSegment(GestureDescription.StrokeDescription part) {
        segmentRunning = true;
        boolean accepted = dispatchGesture(
                new GestureDescription.Builder().addStroke(part).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gd) {
                        main.post(() -> {
                            segmentRunning = false;
                            if (stroke == null) return;
                            if (dragActive) dispatchNextDragSegment();
                            else if (stroke.willContinue()) dispatchFinalDragSegment();
                            else clearStrokeState();
                        });
                    }
                    @Override public void onCancelled(GestureDescription gd) {
                        main.post(HandMouseAccessibilityService.this::clearStrokeState);
                    }
                }, null);
        if (!accepted) {
            segmentRunning = false;
            clearStrokeState();
        }
    }

    private void applyAimAssist() {
        long now = SystemClock.uptimeMillis();
        if (!assistValid || now - lastAssistScanMs >= ASSIST_SCAN_INTERVAL_MS) {
            float[] p = findNearestClickableCenter(cursorX, cursorY, dpFloat(ASSIST_RADIUS_DP));
            lastAssistScanMs = now;
            if (p != null) {
                assistX = p[0]; assistY = p[1]; assistValid = true;
            } else assistValid = false;
        }

        if (!assistValid) return;
        float d = (float)Math.hypot(assistX - cursorX, assistY - cursorY);
        float max = dpFloat(ASSIST_RADIUS_DP);
        if (d > max) { assistValid = false; return; }

        float strength;
        if (d <= dpFloat(ASSIST_STRONG_RADIUS_DP)) strength = 0.72f;
        else strength = 0.24f * (1f - d / max) + 0.10f;
        cursorX += (assistX - cursorX) * strength;
        cursorY += (assistY - cursorY) * strength;
    }

    private float[] findNearestClickableCenter(float x, float y, float radiusPx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        BestNode best = new BestNode(radiusPx);
        scanClickable(root, x, y, best);
        if (!best.found) return null;
        return new float[]{best.x, best.y};
    }

    private boolean clickNearestClickable(float x, float y, float radiusPx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        BestNode best = new BestNode(radiusPx);
        scanClickable(root, x, y, best);
        return best.node != null && best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void scanClickable(AccessibilityNodeInfo node, float x, float y, BestNode best) {
        if (node == null || !node.isVisibleToUser()) return;

        if (node.isClickable() && node.isEnabled()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                float cx = r.exactCenterX();
                float cy = r.exactCenterY();
                float d = (float)Math.hypot(cx - x, cy - y);
                if (d < best.distance) {
                    best.distance = d;
                    best.x = cx;
                    best.y = cy;
                    best.node = node;
                    best.found = true;
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) scanClickable(child, x, y, best);
        }
    }

    private static class BestNode {
        float distance, x, y;
        boolean found;
        AccessibilityNodeInfo node;
        BestNode(float radius) { distance = radius; }
    }

    private void cancelGestureImmediately() {
        main.removeCallbacks(releaseRunnable);
        activeMode = "none";
        dragActive = false;
        if (!segmentRunning && stroke != null) dispatchFinalDragSegment();
        else if (stroke == null) clearStrokeState();
    }

    private void clearStrokeState() {
        stroke = null;
        segmentRunning = false;
        if ("none".equals(activeMode)) dragActive = false;
    }

    private void removeCursor() {
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) {}
        }
        cursor = null;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float dpFloat(float v) { return v * getResources().getDisplayMetrics().density; }
}
