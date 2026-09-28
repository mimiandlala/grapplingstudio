package com.kentaiwamoto.grapplingstudio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;

/** Screen-space crop/ink and a separate, movable front-camera window. */
final class StudioOverlay {
    interface Events { void onRecord(RectF selectedScreenArea); void onStop(); }
    private final Context context;
    private final Handler handler;
    private final WindowManager manager;
    private final Events events;
    private final LinearLayout bar;
    private final DrawingView canvas;
    private final WindowManager.LayoutParams canvasParams;
    private final RectF initial;
    private final int barHeight;
    private boolean selecting = true, drawing = true, closed;
    private CameraBox camera;

    StudioOverlay(Context c, Handler h, RectF initialArea, Events e) {
        context=c; handler=h; initial=initialArea; events=e;
        manager=c.getSystemService(WindowManager.class);
        barHeight=Math.round(60*c.getResources().getDisplayMetrics().density);
        bar=new LinearLayout(c); bar.setBackgroundColor(0xee202020);
        canvas=new DrawingView(c);
        canvasParams=params(-1,barHeight); manager.addView(canvas,canvasParams);
        manager.addView(bar,params(barHeight,0));
        button("Record", v -> {
            if (!selecting) return;
            int[] origin=new int[2]; canvas.getLocationOnScreen(origin);
            RectF selected=canvas.selected(); selected.offset(origin[0],origin[1]);
            selecting=false; canvas.invalidate();
            events.onRecord(selected);
            bar.removeAllViews();
            button("Draw", w -> setDrawing(true)); button("Touch", w -> setDrawing(false));
            button("Clear ink", w -> canvas.clear());
            button("Camera", w -> toggleCamera()); button("Stop", w -> events.onStop());
        });
        button("Use phone", v -> setDrawing(false));
        button("Adjust", v -> setDrawing(true));
        button("Camera", v -> toggleCamera());
        button("Cancel", v -> events.onStop());
        camera=new CameraBox();
        if (initial != null) canvas.post(() -> {
            int[] o=new int[2]; canvas.getLocationOnScreen(o);
            RectF r=new RectF(initial); r.offset(-o[0],-o[1]);
            r.intersect(0,0,canvas.getWidth(),canvas.getHeight());
            if (r.width()>100 && r.height()>100) { canvas.crop=r; canvas.invalidate(); }
        });
    }
    private WindowManager.LayoutParams params(int height,int y) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP; p.y=y; return p;
    }
    private void button(String label,View.OnClickListener click) {
        Button b=new Button(context); b.setText(label); b.setTextSize(10); b.setOnClickListener(click);
        bar.addView(b,new LinearLayout.LayoutParams(0,barHeight,1));
    }
    private void setDrawing(boolean enable) {
        drawing=enable;
        canvasParams.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            (enable ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        manager.updateViewLayout(canvas,canvasParams);
    }
    private void toggleCamera() { if (camera != null) camera.toggle(); }
    void close() {
        if (closed) return; closed=true;
        if (camera != null) camera.close();
        manager.removeViewImmediate(bar); manager.removeViewImmediate(canvas);
    }
    private final class DrawingView extends View {
        private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG), edge=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Path> paths=new ArrayList<>();
        private Path active; private RectF crop;
        private float downX,downY; private boolean resize;
        DrawingView(Context c) {
            super(c);
            pen.setColor(Color.RED); pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(7*getResources().getDisplayMetrics().density);
            pen.setStrokeCap(Paint.Cap.ROUND); pen.setStrokeJoin(Paint.Join.ROUND);
            edge.setColor(Color.YELLOW); edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(3*getResources().getDisplayMetrics().density);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
            if (crop==null) crop=new RectF(w*.07f,h*.06f,w*.93f,h*.78f);
        }
        RectF selected() { return new RectF(crop); }
        void clear() { paths.clear(); active=null; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            for (Path p:paths) c.drawPath(p,pen);
            if (active!=null) c.drawPath(active,pen);
            if (crop!=null) {
                float e=edge.getStrokeWidth(); c.save(); c.clipOutRect(crop);
                c.drawRect(crop.left-e,crop.top-e,crop.right+e,crop.bottom+e,edge);
                if (selecting) c.drawCircle(crop.right,crop.bottom,17*getResources().getDisplayMetrics().density,edge);
                c.restore();
            }
        }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            float x=ev.getX(), y=ev.getY();
            switch(ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (selecting) {
                        downX=x; downY=y;
                        resize=Math.abs(x-crop.right)<65 && Math.abs(y-crop.bottom)<65;
                    } else if (drawing) { active=new Path(); active.moveTo(x,y); invalidate(); }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (selecting) {
                        if (resize) {
                            crop.right=Math.max(crop.left+100,Math.min(getWidth()-5,x));
                            crop.bottom=Math.max(crop.top+100,Math.min(getHeight()-5,y));
                        } else {
                            crop.offset(Math.max(-crop.left,Math.min(getWidth()-crop.right,x-downX)),
                                Math.max(-crop.top,Math.min(getHeight()-crop.bottom,y-downY)));
                            downX=x; downY=y;
                        }
                        invalidate(); return true;
                    }
                    if (active!=null) {
                        for(int j=0;j<ev.getHistorySize();j++) active.lineTo(ev.getHistoricalX(j),ev.getHistoricalY(j));
                        active.lineTo(x,y); invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (active!=null) { active.lineTo(x,y); paths.add(active); active=null; invalidate(); }
                    return true;
            }
            return true;
        }
    }
    private final class CameraBox implements TextureView.SurfaceTextureListener {
        private final FrameLayout panel;
        private final TextureView preview;
        private final WindowManager.LayoutParams position;
        private CameraDevice device;
        private CameraCaptureSession session;
        private Surface surface;
        private Size captureSize=new Size(640,480);
        private boolean shown;
        private float downX,downY;
        private int oldX,oldY,oldW,oldH;
        private boolean resize;
        CameraBox() {
            panel=new FrameLayout(context);
            panel.setBackgroundColor(0xffffd040); panel.setPadding(4,4,4,4);
            preview=new TextureView(context); preview.setSurfaceTextureListener(this);
            panel.addView(preview,new FrameLayout.LayoutParams(-1,-1));
            int d=Math.round(context.getResources().getDisplayMetrics().density);
            position=new WindowManager.LayoutParams(220*d,170*d,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
            position.gravity=Gravity.TOP|Gravity.LEFT;
            position.x=Math.max(0,context.getResources().getDisplayMetrics().widthPixels-position.width-24*d);
            position.y=barHeight+45*d;
            panel.setOnTouchListener((v,event)->{
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX=event.getRawX(); downY=event.getRawY();
                        oldX=position.x; oldY=position.y; oldW=position.width; oldH=position.height;
                        resize=event.getX()>panel.getWidth()-16*d && event.getY()>panel.getHeight()-16*d;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx=Math.round(event.getRawX()-downX), dy=Math.round(event.getRawY()-downY);
                        if (resize) { position.width=Math.max(24*d,Math.min(500*d,oldW+dx)); position.height=Math.max(18*d,Math.min(500*d,oldH+dy)); }
                        else { position.x=Math.max(0,oldX+dx); position.y=Math.max(barHeight,oldY+dy); }
                        manager.updateViewLayout(panel,position); return true;
                    default: return true;
                }
            });
            toggle();
        }
        void toggle() {
            if (shown) { closeCamera(); manager.removeViewImmediate(panel); shown=false; }
            else { manager.addView(panel,position); shown=true; if (preview.isAvailable()) openCamera(); }
        }
        private void openCamera() {
            if (!shown || device!=null) return;
            if (context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return;
            CameraManager cm=context.getSystemService(CameraManager.class);
            try {
                for(String id:cm.getCameraIdList()) {
                    Integer facing=cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (facing!=null && facing==CameraCharacteristics.LENS_FACING_FRONT) {
                        StreamConfigurationMap map=cm.getCameraCharacteristics(id).get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        if (map!=null) {
                            Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
                            if (sizes!=null && sizes.length>0) {
                                captureSize=sizes[0];
                                long best=Long.MAX_VALUE;
                                for(Size size:sizes) {
                                    long score=Math.abs((long)size.getWidth()*1000/size.getHeight()-1300)*1000
                                        +Math.abs((long)size.getWidth()*size.getHeight()-1280L*720)/1000;
                                    if (score<best) { best=score; captureSize=size; }
                                }
                            }
                        }
                        cm.openCamera(id,new CameraDevice.StateCallback() {
                            @Override public void onOpened(CameraDevice d) { device=d; startPreview(); }
                            @Override public void onDisconnected(CameraDevice d) { closeCamera(); }
                            @Override public void onError(CameraDevice d,int error) { closeCamera(); }
                        },handler); return;
                    }
                }
            } catch (Exception ignored) { closeCamera(); }
        }
        private void startPreview() {
            if (!shown || device==null || !preview.isAvailable()) return;
            try {
                SurfaceTexture texture=preview.getSurfaceTexture();
                texture.setDefaultBufferSize(captureSize.getWidth(),captureSize.getHeight());
                surface=new Surface(texture);
                CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(surface);
                device.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session=s;
                        try { s.setRepeatingRequest(request.build(),null,handler); }
                        catch (CameraAccessException ignored) { closeCamera(); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) { closeCamera(); }
                },handler);
            } catch(Exception ignored) { closeCamera(); }
        }
        private void closeCamera() {
            if (session!=null) { session.close(); session=null; }
            if (device!=null) { device.close(); device=null; }
            if (surface!=null) { surface.release(); surface=null; }
        }
        void close() { closeCamera(); if (shown) { manager.removeViewImmediate(panel); shown=false; } }
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { closeCamera(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
    }
}
