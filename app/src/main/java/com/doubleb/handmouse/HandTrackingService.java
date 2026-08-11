package com.doubleb.handmouse;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HandTrackingService extends LifecycleService {
    public interface Listener {
        void onTrackingFrame(float x, float y, String gesture);
        void onTrackingStatus(String status);
    }

    private static volatile Listener listener;
    private static volatile boolean running;
    private static volatile String status = "aus";
    private static final String CHANNEL = "hand_tracking";
    private static final int NOTIFICATION_ID = 77;

    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private HandLandmarker handLandmarker;
    private long lastTimestamp;
    private String currentGesture = "none";
    private String pinchCandidate = "none";
    private int pinchCandidateFrames;
    private int pinchReleaseFrames;

    // Strict pinch hysteresis. Enter is intentionally much tighter than release.
    private static final float LEFT_ENTER = 0.24f;
    private static final float LEFT_RELEASE = 0.34f;
    private static final float RIGHT_ENTER = 0.23f;
    private static final float RIGHT_RELEASE = 0.34f;
    private static final float RIGHT_INDEX_CLEAR_ENTER = 0.46f;
    private static final float RIGHT_INDEX_CLEAR_RELEASE = 0.36f;
    private static final int PINCH_ENTER_FRAMES = 3;
    private static final int PINCH_RELEASE_FRAMES = 2;
    private boolean fistLatched;
    private int fistReleaseFrames;
    private float smoothX = 0.5f, smoothY = 0.5f;
    private boolean haveSmooth;

    public static void setListener(Listener l) { listener = l; }
    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }

    @Override
    public void onCreate() {
        super.onCreate();
        cameraExecutor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        startForeground(NOTIFICATION_ID, notification("Kamera wird gestartet …"));
        if (!running) startNativeTracking();
        return START_STICKY;
    }

    private void startNativeTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("FEHLER: Kamera-Berechtigung fehlt");
            stopSelf();
            return;
        }
        try {
            setStatus("MediaPipe wird geladen …");
            BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            HandLandmarker.HandLandmarkerOptions opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.45f)
                    .setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.45f)
                    .setResultListener(this::onResult)
                    .setErrorListener(e -> setStatus("MediaPipe-Fehler: " + e.getMessage()))
                    .build();
            handLandmarker = HandLandmarker.createFromOptions(this, opts);
            setStatus("MediaPipe bereit – Kamera wird geöffnet …");
            openCamera();
        } catch (Throwable t) {
            setStatus("MediaPipe-Startfehler: " + t.getMessage());
            stopSelf();
        }
    }

    private void openCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                running = true;
                setStatus("Kamera aktiv – zeig deine Hand");
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFICATION_ID, notification("Kamera + Handtracking aktiv"));
            } catch (Throwable t) {
                setStatus("Kamera-Fehler: " + t.getMessage());
                stopSelf();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            Bitmap b = image.toBitmap();
            int rotation = image.getImageInfo().getRotationDegrees();
            Matrix m = new Matrix();
            if (rotation != 0) m.postRotate(rotation);
            m.postScale(-1f, 1f);
            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            MPImage mp = new BitmapImageBuilder(rotated).build();
            long ts = SystemClock.uptimeMillis();
            if (ts <= lastTimestamp) ts = lastTimestamp + 1;
            lastTimestamp = ts;
            handLandmarker.detectAsync(mp, ts);
        } catch (Throwable t) {
            setStatus("Frame-Fehler: " + t.getMessage());
        } finally {
            image.close();
        }
    }

    private void onResult(HandLandmarkerResult result, MPImage image) {
        if (result.landmarks().isEmpty()) {
            currentGesture = "none";
            sendFrame(smoothX, smoothY, "none");
            setStatus("Kamera aktiv – keine Hand erkannt");
            return;
        }
        List<NormalizedLandmark> lm = result.landmarks().get(0);
        float x = (lm.get(4).x() + lm.get(8).x()) * 0.5f;
        float y = (lm.get(4).y() + lm.get(8).y()) * 0.5f;
        if (!haveSmooth) { smoothX = x; smoothY = y; haveSmooth = true; }
        else { smoothX += (x - smoothX) * 0.62f; smoothY += (y - smoothY) * 0.62f; }
        String g = gesture(lm);
        currentGesture = g;
        sendFrame(smoothX, smoothY, g);
        setStatus("Hand erkannt – " + g);
    }

    private String gesture(List<NormalizedLandmark> lm) {
        if (isFullFist(lm)) {
            pinchCandidate = "none";
            pinchCandidateFrames = 0;
            pinchReleaseFrames = 0;
            return "fist";
        }

        float scale = Math.max(dist(lm,5,17), Math.max(dist(lm,0,9), 0.05f));
        float ti = dist(lm,4,8) / scale;
        float tm = dist(lm,4,12) / scale;

        // Once a pinch is active, keep it with a wider RELEASE threshold.
        // This prevents flicker without making initial activation too easy.
        if ("left".equals(currentGesture)) {
            if (ti <= LEFT_RELEASE) {
                pinchReleaseFrames = 0;
                return "left";
            }
            if (++pinchReleaseFrames < PINCH_RELEASE_FRAMES) return "left";
            pinchReleaseFrames = 0;
            pinchCandidate = "none";
            pinchCandidateFrames = 0;
            return "none";
        }

        if ("right".equals(currentGesture)) {
            if (tm <= RIGHT_RELEASE && ti >= RIGHT_INDEX_CLEAR_RELEASE) {
                pinchReleaseFrames = 0;
                return "right";
            }
            if (++pinchReleaseFrames < PINCH_RELEASE_FRAMES) return "right";
            pinchReleaseFrames = 0;
            pinchCandidate = "none";
            pinchCandidateFrames = 0;
            return "none";
        }

        // New gestures require three consecutive confident frames.
        // Right pinch is checked first and explicitly requires the index finger away.
        String raw = "none";
        if (tm <= RIGHT_ENTER && ti >= RIGHT_INDEX_CLEAR_ENTER) raw = "right";
        else if (ti <= LEFT_ENTER) raw = "left";

        if ("none".equals(raw)) {
            pinchCandidate = "none";
            pinchCandidateFrames = 0;
            return "none";
        }

        if (raw.equals(pinchCandidate)) pinchCandidateFrames++;
        else {
            pinchCandidate = raw;
            pinchCandidateFrames = 1;
        }

        if (pinchCandidateFrames >= PINCH_ENTER_FRAMES) {
            pinchCandidate = "none";
            pinchCandidateFrames = 0;
            pinchReleaseFrames = 0;
            return raw;
        }
        return "none";
    }

    private boolean isFullFist(List<NormalizedLandmark> lm) {
        float s1 = bend(lm,5,6,7,8), s2 = bend(lm,9,10,11,12), s3 = bend(lm,13,14,15,16), s4 = bend(lm,17,18,19,20);
        float min = Math.min(Math.min(s1,s2), Math.min(s3,s4));
        float avg = (s1+s2+s3+s4)/4f;
        float score = Math.min(avg, min*1.08f);
        if (!fistLatched) {
            if (score >= 0.72f) { fistLatched = true; fistReleaseFrames = 0; }
        } else if (score < 0.48f) {
            if (++fistReleaseFrames >= 5) { fistLatched = false; fistReleaseFrames = 0; }
        } else fistReleaseFrames = 0;
        return fistLatched;
    }

    private float bend(List<NormalizedLandmark> lm,int mcp,int pip,int dip,int tip) {
        float pa = angle(lm.get(mcp),lm.get(pip),lm.get(dip));
        float da = angle(lm.get(pip),lm.get(dip),lm.get(tip));
        float ps = clamp((155f-pa)/55f), ds = clamp((165f-da)/60f);
        return ps*0.68f + ds*0.32f;
    }

    private float angle(NormalizedLandmark a, NormalizedLandmark b, NormalizedLandmark c) {
        float x1=a.x()-b.x(), y1=a.y()-b.y(), x2=c.x()-b.x(), y2=c.y()-b.y();
        double den=Math.hypot(x1,y1)*Math.hypot(x2,y2); if(den<1e-6) return 180f;
        double v=(x1*x2+y1*y2)/den; v=Math.max(-1,Math.min(1,v)); return (float)Math.toDegrees(Math.acos(v));
    }

    private float dist(List<NormalizedLandmark> lm,int a,int b) {
        float dx=lm.get(a).x()-lm.get(b).x(), dy=lm.get(a).y()-lm.get(b).y(); return (float)Math.hypot(dx,dy);
    }
    private float clamp(float v){ return Math.max(0f, Math.min(1f,v)); }

    private void sendFrame(float x,float y,String g){ Listener l=listener; if(l!=null) l.onTrackingFrame(x,y,g); }
    private void setStatus(String s){ status=s; Listener l=listener; if(l!=null) l.onTrackingStatus(s); }

    private void createChannel(){ NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Hand Mouse Kamera",NotificationManager.IMPORTANCE_LOW)); }
    private Notification notification(String text){ return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Hand Mouse").setContentText(text).setOngoing(true).build(); }

    @Override
    public void onDestroy() {
        running=false;
        if(cameraProvider!=null) cameraProvider.unbindAll();
        if(handLandmarker!=null) try{ handLandmarker.close(); }catch(Exception ignored){}
        if(cameraExecutor!=null) cameraExecutor.shutdownNow();
        setStatus("aus");
        super.onDestroy();
    }
}
