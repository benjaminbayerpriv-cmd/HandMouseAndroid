package com.doubleb.handmouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class HandMouseAccessibilityService extends AccessibilityService implements HandTrackingService.Listener {
    private static volatile HandMouseAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams params;
    private int screenW, screenH;

    // Tracking target and independently animated cursor position.
    private float targetX, targetY;
    private float cursorX, cursorY;
    private boolean animatorRunning;
    private static final long ANIM_MS = 16L;          // ~60 Hz
    private static final float CURSOR_EASE = 0.34f;  // smooth but responsive
    private static final float TARGET_DEADZONE_PX = 1.4f;

    // Pinch state machine.
    private String activeMode = "none"; // none / left / right
    private long pinchStartMs;
    private float pinchStartX, pinchStartY;
    private boolean dragActive;
    private static final long LEFT_DRAG_HOLD_MS = 300L;
    private static final long RIGHT_DRAG_HOLD_MS = 520L;
    private static final float DRAG_MOVE_THRESHOLD_DP = 18f;
    private static final long RELEASE_GRACE_MS = 85L;

    // Continued accessibility drag state.
    private GestureDescription.StrokeDescription stroke;
    private boolean segmentRunning;
    private float strokeEndX, strokeEndY;
    private float desiredDragX, desiredDragY;

    private final Runnable releaseRunnable = () -> finishPinch(false);

    private final Runnable animator = new Runnable() {
        @Override public void run() {
            if (!animatorRunning) return;
            float dx = targetX - cursorX;
            float dy = targetY - cursorY;
            float dist = (float)Math.hypot(dx, dy);
            if (dist < 0.65f) {
                cursorX = targetX;
                cursorY = targetY;
            } else {
                // Slightly stronger catch-up for big movements, gentler for micro-jitter.
                float boost = Math.min(0.22f, dist / Math.max(1f, screenW) * 1.7f);
                float a = Math.min(0.62f, CURSOR_EASE + boost);
                cursorX += dx * a;
                cursorY += dy * a;
            }
            moveCursorOverlay();
            if (dragActive) {
                desiredDragX = cursorX;
                desiredDragY = cursorY;
            }
            maybeStartHeldDrag();
            main.postDelayed(this, ANIM_MS);
        }
    };

    public static HandMouseAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        createCursor();
        HandTrackingService.setListener(this);
        animatorRunning = true;
        main.post(animator);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent i) {
        animatorRunning = false;
        main.removeCallbacks(animator);
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
        targetX = cursorX = screenW / 2f;
        targetY = cursorY = screenH / 2f;
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
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));
        if (g == null) g = "none";

        float newX = nx * screenW;
        float newY = ny * screenH;

        if (!"fist".equals(g)) {
            if (Math.hypot(newX - targetX, newY - targetY) >= TARGET_DEADZONE_PX) {
                targetX = newX;
                targetY = newY;
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
                // Gesture changed directly left<->right: finish old one, then begin new one.
                finishPinch(true);
                startPinch(g);
            }
            maybeStartHeldDrag();
            return;
        }

        // A 1-2 frame tracking dropout should not accidentally release a click/drag.
        if (!"none".equals(activeMode)) {
            main.removeCallbacks(releaseRunnable);
            main.postDelayed(releaseRunnable, RELEASE_GRACE_MS);
        }
    }

    private void startPinch(String mode) {
        activeMode = mode;
        pinchStartMs = SystemClock.uptimeMillis();
        pinchStartX = cursorX;
        pinchStartY = cursorY;
        dragActive = false;
        clearStrokeState();
    }

    private void maybeStartHeldDrag() {
        if ("none".equals(activeMode) || dragActive || segmentRunning) return;
        long held = SystemClock.uptimeMillis() - pinchStartMs;
        float moved = (float)Math.hypot(cursorX - pinchStartX, cursorY - pinchStartY);
        long threshold = "right".equals(activeMode) ? RIGHT_DRAG_HOLD_MS : LEFT_DRAG_HOLD_MS;
        if (held >= threshold || moved >= dpFloat(DRAG_MOVE_THRESHOLD_DP)) {
            beginDrag(activeMode);
        }
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
            // Short pinch = one clean action on release. No continuous gesture was started.
            if ("left".equals(mode)) dispatchTap(cursorX, cursorY);
            else if ("right".equals(mode)) dispatchLongPress(cursorX, cursorY);
        }
        clearStrokeState();
    }

    private void dispatchTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 55, false);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void dispatchLongPress(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 560, false);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void beginDrag(String mode) {
        if (dragActive || segmentRunning) return;
        dragActive = true;
        strokeEndX = desiredDragX = cursorX;
        strokeEndY = desiredDragY = cursorY;
        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        long initialHold = "right".equals(mode) ? 520L : 40L;
        stroke = new GestureDescription.StrokeDescription(p, 0, initialHold, true);
        dispatchDragSegment(stroke);
    }

    private void endDrag() {
        if (!dragActive) {
            clearStrokeState();
            return;
        }
        dragActive = false;
        // If the current segment is running, callback will finish it with willContinue=false.
        if (!segmentRunning) dispatchFinalDragSegment();
    }

    private void dispatchNextDragSegment() {
        if (stroke == null) return;
        Path p = new Path();
        p.moveTo(strokeEndX, strokeEndY);
        p.lineTo(desiredDragX, desiredDragY);
        try {
            stroke = stroke.continueStroke(p, 0, 34L, true);
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
            stroke = stroke.continueStroke(p, 0, 12L, false);
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

    private void cancelGestureImmediately() {
        main.removeCallbacks(releaseRunnable);
        activeMode = "none";
        dragActive = false;
        // Android doesn't expose a synchronous cancel; stopping continuation makes the next callback terminate it.
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
