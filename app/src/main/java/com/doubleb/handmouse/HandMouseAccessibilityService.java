package com.doubleb.handmouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class HandMouseAccessibilityService extends AccessibilityService implements HandTrackingService.Listener {
    private static volatile HandMouseAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm; private CursorView cursor; private WindowManager.LayoutParams params;
    private int screenW,screenH; private float cursorX,cursorY,desiredX,desiredY,strokeEndX,strokeEndY;
    private String gesture="none",pendingMode="none"; private GestureDescription.StrokeDescription stroke;
    private boolean strokeActive,strokeHeld,segmentRunning;
    public static HandMouseAccessibilityService getInstance(){ return instance; }

    @Override protected void onServiceConnected(){ super.onServiceConnected(); instance=this; wm=(WindowManager)getSystemService(WINDOW_SERVICE); createCursor(); HandTrackingService.setListener(this); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    @Override public boolean onUnbind(android.content.Intent i){ removeCursor(); HandTrackingService.setListener(null); instance=null; return super.onUnbind(i); }

    private void createCursor(){
        android.util.DisplayMetrics dm=getResources().getDisplayMetrics(); screenW=dm.widthPixels; screenH=dm.heightPixels; cursorX=screenW/2f; cursorY=screenH/2f;
        cursor=new CursorView(this); int size=dp(38); params=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT; moveCursor(); wm.addView(cursor,params); cursor.setVisibility(android.view.View.VISIBLE);
    }
    private void moveCursor(){ if(params==null)return; params.x=Math.round(cursorX)-dp(3); params.y=Math.round(cursorY)-dp(2); if(cursor!=null && cursor.isAttachedToWindow()) try{wm.updateViewLayout(cursor,params);}catch(Exception ignored){} }

    @Override public void onTrackingFrame(float x,float y,String g){ main.post(()->handleFrame(x,y,g)); }
    @Override public void onTrackingStatus(String s){}
    private void handleFrame(float nx,float ny,String g){
        nx=Math.max(0,Math.min(1,nx)); ny=Math.max(0,Math.min(1,ny)); if(g==null)g="none";
        if(!"fist".equals(g)){ cursorX=nx*screenW; cursorY=ny*screenH; desiredX=cursorX; desiredY=cursorY; moveCursor(); }
        cursor.setMode(g);
        if("fist".equals(g)||"none".equals(g)){ if(!"none".equals(gesture)) endStroke(); gesture=g; return; }
        if(!g.equals(gesture)){ if(!"none".equals(gesture)&&!"fist".equals(gesture)){ pendingMode=g; endStroke(); } else beginStroke(g); gesture=g; }
        else if(strokeActive){ desiredX=cursorX; desiredY=cursorY; }
    }
    private void beginStroke(String mode){ if(strokeActive||segmentRunning){pendingMode=mode;return;} strokeHeld=true;strokeActive=true;strokeEndX=desiredX=cursorX;strokeEndY=desiredY=cursorY;Path p=new Path();p.moveTo(strokeEndX,strokeEndY);long d="right".equals(mode)?550L:75L;stroke=new GestureDescription.StrokeDescription(p,0,d,true);dispatchSegment(stroke); }
    private void endStroke(){ if(!strokeActive){strokeHeld=false;return;} strokeHeld=false; if(!segmentRunning)dispatchNext(); }
    private void dispatchNext(){ if(!strokeActive||stroke==null)return; Path p=new Path();p.moveTo(strokeEndX,strokeEndY);p.lineTo(desiredX,desiredY);boolean keep=strokeHeld;try{stroke=stroke.continueStroke(p,0,keep?55L:1L,keep);strokeEndX=desiredX;strokeEndY=desiredY;dispatchSegment(stroke);}catch(Exception e){clearStroke();} }
    private void dispatchSegment(GestureDescription.StrokeDescription part){ segmentRunning=true; boolean ok=dispatchGesture(new GestureDescription.Builder().addStroke(part).build(),new GestureResultCallback(){@Override public void onCompleted(GestureDescription gd){main.post(()->{segmentRunning=false;if(!strokeActive)return;if(!strokeHeld&&!stroke.willContinue()){clearStroke();startPending();}else dispatchNext();});}@Override public void onCancelled(GestureDescription gd){main.post(()->{segmentRunning=false;clearStroke();startPending();});}},null); if(!ok){segmentRunning=false;clearStroke();startPending();} }
    private void clearStroke(){stroke=null;strokeActive=false;strokeHeld=false;segmentRunning=false;}
    private void startPending(){String p=pendingMode;pendingMode="none";if(!"none".equals(p)&&!"fist".equals(p)&&p.equals(gesture))beginStroke(p);}
    private void removeCursor(){if(cursor!=null&&wm!=null)try{wm.removeView(cursor);}catch(Exception ignored){} cursor=null;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
