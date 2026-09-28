package com.kentaiwamoto.grapplingstudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;

/** Records the default display once; preserves the original before exporting any crop. */
public class CaptureService extends Service {
    static final String START="start", STOP="stop";
    private static final String CHANNEL="studio-record";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private MediaProjection projection;
    private VirtualDisplay display;
    private StudioOverlay overlay;
    private AudioManager audio;
    private File raw,cropped;
    private RectF selected;
    private int screenWidth,screenHeight;
    private boolean recording,exporting,finishing;
    private long beganAt;
    private String status="Ready";
    @Override public void onCreate() {
        super.onCreate(); audio=getSystemService(AudioManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Grappling recordings",NotificationManager.IMPORTANCE_DEFAULT));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null) return START_NOT_STICKY;
        if(STOP.equals(intent.getAction())) { finish(); return START_NOT_STICKY; }
        if(START.equals(intent.getAction())) {
            if(overlay!=null || recording || exporting) return START_NOT_STICKY;
            startForeground(1,notification("Choose crop and cue your video"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION |
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE |
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            try {
                RectF bounds=intent.getParcelableExtra("videoBounds");
                overlay=new StudioOverlay(this,handler,bounds,new StudioOverlay.Events() {
                    @Override public void onRecord(RectF area) {
                        selected=area;
                        try { begin(intent); }
                        catch(Exception ex) { status="Start failed: "+ex.getMessage(); finish(); }
                    }
                    @Override public void onStop() { finish(); }
                });
            } catch(Exception ex) { status="Overlay failed: "+ex.getMessage(); finish(); }
        }
        return START_NOT_STICKY;
    }
    private void begin(Intent intent) throws IOException {
        int wanted=intent.getIntExtra("inputId",-1);
        AudioDeviceInfo input=null;
        for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) if(d.getId()==wanted) input=d;
        if(input==null || !isBluetooth(input)) throw new IOException("DJI Bluetooth mic disconnected");
        raw=new File(getCacheDir(),"raw-"+System.currentTimeMillis()+".mp4");
        DisplayMetrics metrics=new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        screenWidth=metrics.widthPixels&~1; screenHeight=metrics.heightPixels&~1;
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        for(AudioDeviceInfo d:audio.getAvailableCommunicationDevices())
            if(d.getType()==input.getType()) { audio.setCommunicationDevice(d); break; }
        recorder=new MediaRecorder(this);
        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128000); recorder.setAudioSamplingRate(44100);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(screenWidth,screenHeight);
        recorder.setVideoFrameRate(60); recorder.setVideoEncodingBitRate(32000000);
        recorder.setOutputFile(raw.getAbsolutePath());
        if(!recorder.setPreferredDevice(input)) throw new IOException("DJI mic route rejected");
        recorder.prepare();
        Intent consent=intent.getParcelableExtra("data");
        projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result",0),consent);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { handler.post(()->finish()); }
        },handler);
        display=projection.createVirtualDisplay("Studio default display",screenWidth,screenHeight,metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,handler);
        recorder.start(); recording=true; beganAt=SystemClock.elapsedRealtime();
        status="Recording with DJI mic"; notifyStatus();
        handler.postDelayed(()->{
            if(!recording || recorder==null) return;
            AudioDeviceInfo routed=recorder.getRoutedDevice();
            if(routed==null || routed.getId()!=wanted || !isBluetooth(routed)) {
                status="DJI mic lost; stopping"; finish();
            }
        },2000);
    }
    private boolean isBluetooth(AudioDeviceInfo d) {
        return d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET;
    }
    private Notification notification(String message) {
        Intent stop=new Intent(this,CaptureService.class); stop.setAction(STOP);
        PendingIntent action=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Grappling Screen Studio").setContentText(message)
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Stop",action).build();
    }
    private void notifyStatus() { getSystemService(NotificationManager.class).notify(1,notification(status)); }
    private void finish() {
        if(finishing || exporting) return;
        finishing=true;
        boolean valid=recording; recording=false;
        long elapsed=SystemClock.elapsedRealtime()-beganAt;
        // Stop the encoder while the projection surface is still alive.
        if(recorder!=null) {
            if(valid) try { recorder.stop(); } catch(RuntimeException ex) { valid=false; status="Recorder stop failed"; }
            recorder.reset(); recorder.release(); recorder=null;
        }
        if(display!=null) { display.release(); display=null; }
        if(projection!=null) { MediaProjection p=projection; projection=null; p.stop(); }
        if(overlay!=null) { overlay.close(); overlay=null; }
        audio.clearCommunicationDevice(); audio.setMode(AudioManager.MODE_NORMAL);
        if(valid && raw!=null && selected!=null) {
            try {
                String name="Studio-Original-"+System.currentTimeMillis()+".mp4";
                publish(raw,name,"Movies/Grappling Screen Studio/Originals");
                TrackTimes times=inspect(raw);
                if(times.videoLastUs<=0 || times.audioLastUs-times.videoLastUs>2000000 ||
                    Math.abs(elapsed*1000-times.audioLastUs)>3000000) {
                    status="Original saved; capture has a timing gap, crop skipped"; notifyStatus(); cleanup(); return;
                }
                startExport(); return;
            } catch(Exception ex) { status="Could not save original: "+ex.getMessage(); notifyStatus(); }
        }
        if(raw!=null && !valid) status="Recorder produced no usable MP4; try again";
        notifyStatus(); cleanup();
    }
    private static final class TrackTimes { long videoLastUs=-1,audioLastUs=-1; }
    private TrackTimes inspect(File source) throws IOException {
        TrackTimes result=new TrackTimes(); MediaExtractor ex=new MediaExtractor();
        try {
            ex.setDataSource(source.getAbsolutePath());
            for(int track=0;track<ex.getTrackCount();track++) {
                MediaFormat f=ex.getTrackFormat(track);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime==null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                ex.selectTrack(track);
                ex.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long last=-1;
                while(true) {
                    int index=ex.getSampleTrackIndex();
                    if(index<0) break;
                    if(index==track) { last=Math.max(last,ex.getSampleTime()); }
                    if(!ex.advance()) break;
                }
                if(mime.startsWith("video/")) result.videoLastUs=last; else result.audioLastUs=last;
                ex.unselectTrack(track);
            }
        } finally { ex.release(); }
        if(result.videoLastUs<0 || result.audioLastUs<0) throw new IOException("Missing video or audio track");
        return result;
    }
    private void startExport() {
        exporting=true; status="Original saved; cropping selected area…"; notifyStatus();
        cropped=new File(getCacheDir(),"crop-"+System.currentTimeMillis()+".mp4");
        float left=Math.max(0f,Math.min(.95f,selected.left/screenWidth));
        float right=Math.min(1f,Math.max(left+.05f,selected.right/screenWidth));
        float top=Math.max(0f,Math.min(.95f,selected.top/screenHeight));
        float bottom=Math.min(1f,Math.max(top+.05f,selected.bottom/screenHeight));
        Crop crop=new Crop(left*2-1,right*2-1,1-bottom*2,1-top*2);
        EditedMediaItem item=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(raw)))
            .setEffects(new Effects(Collections.emptyList(),Collections.singletonList(crop))).build();
        try {
            new Transformer.Builder(this).setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(24000000).build()).build())
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition,ExportResult result) {
                        try {
                            TrackTimes original=inspect(raw), resultTimes=inspect(cropped);
                            if(original.videoLastUs-resultTimes.videoLastUs>2000000 ||
                               original.audioLastUs-resultTimes.audioLastUs>2000000)
                                throw new IOException("Export lost ending; original retained");
                            publish(cropped,"Studio-Crop-"+System.currentTimeMillis()+".mp4","Movies/Grappling Screen Studio/Crops");
                            status="Original and crop saved";
                        } catch(Exception ex) { status="Original saved; crop failed: "+ex.getMessage(); }
                        notifyStatus(); cleanup();
                    }
                    @Override public void onError(Composition c,ExportResult r,ExportException ex) {
                        status="Original saved; crop failed: "+ex.getMessage(); notifyStatus(); cleanup();
                    }
                }).build().start(item,cropped.getAbsolutePath());
        } catch(Exception ex) { status="Original saved; crop failed: "+ex.getMessage(); notifyStatus(); cleanup(); }
    }
    private void publish(File source,String name,String directory) throws IOException {
        ContentValues values=new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,directory);
        values.put(MediaStore.Video.Media.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Cannot create gallery entry");
        try(FileInputStream in=new FileInputStream(source); java.io.OutputStream out=getContentResolver().openOutputStream(uri)) {
            if(out==null) throw new IOException("Cannot open gallery entry");
            byte[] buf=new byte[65536]; int n;
            while((n=in.read(buf))>=0) out.write(buf,0,n);
        } catch(Exception ex) {
            getContentResolver().delete(uri,null,null); throw new IOException(ex);
        }
        ContentValues done=new ContentValues(); done.put(MediaStore.Video.Media.IS_PENDING,0);
        getContentResolver().update(uri,done,null,null);
    }
    private void cleanup() {
        exporting=false; if(raw!=null) { raw.delete(); raw=null; }
        if(cropped!=null) { cropped.delete(); cropped=null; }
        stopForeground(STOP_FOREGROUND_DETACH); stopSelf();
    }
    @Override public void onDestroy() { if(!finishing) finish(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
