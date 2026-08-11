package com.doubleb.handmouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String mode = "none";

    public CursorView(Context context) {
        super(context);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
    }

    public void setMode(String mode) {
        this.mode = mode == null ? "none" : mode;
        if ("left".equals(this.mode)) fill.setColor(0xff56f08a);
        else if ("right".equals(this.mode)) fill.setColor(0xffff9d55);
        else fill.setColor(Color.WHITE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        Path p = new Path();
        p.moveTo(3*d, 2*d);
        p.lineTo(3*d, 28*d);
        p.lineTo(10*d, 21*d);
        p.lineTo(15*d, 31*d);
        p.lineTo(20*d, 28*d);
        p.lineTo(15*d, 18*d);
        p.lineTo(25*d, 17*d);
        p.close();
        canvas.drawPath(p, fill);
        canvas.drawPath(p, stroke);
    }
}
