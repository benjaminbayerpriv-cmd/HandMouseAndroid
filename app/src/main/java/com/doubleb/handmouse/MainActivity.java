package com.doubleb.handmouse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA=1001; private TextView status;
    @Override protected void onCreate(Bundle b){super.onCreate(b);buildUi();requestCameraIfNeeded();}
    @Override protected void onResume(){super.onResume();updateStatus();}
    private void buildUi(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(dp(24),dp(42),dp(24),dp(24));r.setBackgroundColor(Color.BLACK);
        TextView t=new TextView(this);t.setText("Hand Mouse v2");t.setTextColor(Color.WHITE);t.setTextSize(30);t.setGravity(Gravity.CENTER);r.addView(t,full(dp(70)));
        TextView d=new TextView(this);d.setText("Native CameraX + MediaPipe. Läuft nach Home im Vordergrund-Service weiter.\n\n🤏 Daumen + Zeige: Klick / Drag\n✌ Daumen + Mitte ohne Zeige: Long-Press / Drag\n✊ Faust: Cursor einfrieren");d.setTextColor(0xffcccccc);d.setTextSize(16);d.setGravity(Gravity.CENTER);r.addView(d,full(dp(180)));
        status=new TextView(this);status.setTextColor(0xffffd54f);status.setTextSize(15);status.setGravity(Gravity.CENTER);r.addView(status,full(dp(110)));
        Button a=button("1. Kamera erlauben");a.setOnClickListener(v->requestCameraIfNeeded());r.addView(a,full(dp(58)));
        Button x=button("2. Bedienungshilfe aktivieren");x.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));r.addView(x,top(dp(58),12));
        Button s=button("3. Handtracking starten");s.setOnClickListener(v->startTracking());r.addView(s,top(dp(58),12));
        Button q=button("Tracking stoppen");q.setOnClickListener(v->{stopService(new Intent(this,HandTrackingService.class));Toast.makeText(this,"Tracking gestoppt",Toast.LENGTH_SHORT).show();updateStatus();});r.addView(q,top(dp(58),12));
        setContentView(r);updateStatus();
    }
    private void startTracking(){ if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestCameraIfNeeded();return;} if(HandMouseAccessibilityService.getInstance()==null){Toast.makeText(this,"Erst Bedienungshilfe aktivieren",Toast.LENGTH_LONG).show();startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;} ContextCompat.startForegroundService(this,new Intent(this,HandTrackingService.class)); Toast.makeText(this,"Kamera startet. Danach kannst du Home drücken.",Toast.LENGTH_LONG).show(); new android.os.Handler().postDelayed(this::updateStatus,1200); }
    private void updateStatus(){ boolean cam=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED; boolean acc=HandMouseAccessibilityService.getInstance()!=null; status.setText("Kamera-Recht: "+(cam?"✓":"✗")+"\nBedienungshilfe: "+(acc?"✓":"✗")+"\nTracking: "+(HandTrackingService.isRunning()?"AKTIV":"aus")+"\nStatus: "+HandTrackingService.getStatus()); }
    private void requestCameraIfNeeded(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);return b;}
    private LinearLayout.LayoutParams full(int h){return new LinearLayout.LayoutParams(-1,h);} private LinearLayout.LayoutParams top(int h,int m){LinearLayout.LayoutParams p=full(h);p.topMargin=dp(m);return p;} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
