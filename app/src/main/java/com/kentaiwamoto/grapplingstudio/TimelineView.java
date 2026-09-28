package com.kentaiwamoto.grapplingstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** A fixed playhead with a draggable filmstrip; holding zooms for precise seeking. */
final class TimelineView extends View {
    interface SeekListener { void seekTo(int milliseconds); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Bitmap[] frames=new Bitmap[0];
    private int duration,position,generation;
    private boolean touching,zoomed;
    private float lastX,scaledDensity;
    private SeekListener listener;
    private final Runnable longPress=()->{ if(touching) { zoomed=true; invalidate(); } };

    TimelineView(Context context) {
        super(context);
        scaledDensity=getResources().getDisplayMetrics().density;
        setBackgroundColor(0xff1b1d20);
    }
    void setSeekListener(SeekListener callback) { listener=callback; }
    void setPosition(int millis) {
        if(touching) return;
        position=Math.max(0,Math.min(duration,millis)); invalidate();
    }
    void setVideo(Uri uri,int millis) {
        generation++;
        final int request=generation;
        duration=Math.max(1,millis); position=0; frames=new Bitmap[0]; invalidate();
        if(uri==null) return;
        new Thread(()->{
            Bitmap[] result=new Bitmap[24];
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getContext().getApplicationContext(),uri);
                for(int i=0;i<result.length;i++) {
                    Bitmap frame=retriever.getFrameAtTime((long)duration*1000*i/result.length,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if(frame!=null) result[i]=Bitmap.createScaledBitmap(frame,92,54,false);
                    if(request!=generation) return;
                }
            } catch(Exception ignored) { } finally { try { retriever.release(); } catch(Exception ignored) { } }
            handler.post(()->{ if(request==generation) { frames=result; invalidate(); } });
        },"timeline-thumbnails").start();
    }
    @Override protected void onDraw(Canvas c) {
        float d=scaledDensity,center=getWidth()/2f;
        float pixelsPerSecond=(zoomed?64f:16f)*d;
        paint.setColor(Color.DKGRAY); c.drawRect(0,0,getWidth(),getHeight(),paint);
        for(int i=0;i<frames.length;i++) {
            float ms=(float)duration*i/frames.length;
            float x=center+(ms-position)*pixelsPerSecond/1000f;
            if(x>getWidth() || x+80*d<0) continue;
            Bitmap frame=frames[i];
            if(frame!=null) {
                paint.setColor(Color.WHITE);
                c.drawBitmap(frame,null,new android.graphics.RectF(x,17*d,x+80*d,63*d),paint);
            }
        }
        int seconds=(int)Math.ceil(getWidth()/pixelsPerSecond)+1;
        paint.setColor(Color.LTGRAY); paint.setTextSize(10*d);
        for(int s=Math.max(0,position/1000-seconds);s<=duration/1000 && s<=position/1000+seconds;s++) {
            float x=center+(s*1000f-position)*pixelsPerSecond/1000f;
            if(x<0 || x>getWidth()) continue;
            c.drawLine(x,0,x,(s%5==0?12:6)*d,paint);
            if(s%5==0) c.drawText((s/60)+":"+String.format(java.util.Locale.US,"%02d",s%60),x+2*d,12*d,paint);
        }
        paint.setColor(0xffffd040); paint.setStrokeWidth(2*d);
        c.drawLine(center,0,center,getHeight(),paint);
        paint.setColor(Color.WHITE); paint.setTextSize(11*d);
        c.drawText(zoomed?"Fine seek ×4":"Hold for fine seek",5*d,getHeight()-3*d,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching=true; zoomed=false; lastX=event.getX();
                handler.postDelayed(longPress,450); return true;
            case MotionEvent.ACTION_MOVE:
                float x=event.getX(), delta=x-lastX;
                if(!zoomed && Math.abs(delta)>8*scaledDensity) handler.removeCallbacks(longPress);
                float pixelsPerSecond=(zoomed?64f:16f)*scaledDensity;
                position=Math.max(0,Math.min(duration,position-Math.round(delta*1000f/pixelsPerSecond)));
                lastX=x; invalidate();
                if(listener!=null) listener.seekTo(position);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress); touching=false; zoomed=false; invalidate(); return true;
        }
        return true;
    }
}
