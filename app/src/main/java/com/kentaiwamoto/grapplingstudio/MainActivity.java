package com.kentaiwamoto.grapplingstudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 1, CAPTURE = 2, PERMISSIONS = 3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private TextView status, time;
    private SeekBar seek;
    private AudioManager audio;
    private int micId = -1;
    private boolean dragging;
    private boolean videoReady;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (videoReady && video != null) {
                seek.setMax(Math.max(1, video.getDuration()));
                if (!dragging) seek.setProgress(video.getCurrentPosition());
                time.setText(format(video.getCurrentPosition()) + " / " + format(video.getDuration()));
            }
            handler.postDelayed(this, 300);
        }
    };
    private String format(int ms) { int sec = Math.max(0, ms / 1000); return (sec / 60) + ":" + String.format(java.util.Locale.US, "%02d", sec % 60); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        status = new TextView(this); status.setTextSize(15);
        status.setText("Choose a video or prepare full-screen recording. The microphone records your voice.");
        root.addView(status);
        LinearLayout top = row(root);
        add(top, "Open video", v -> openVideo());
        add(top, "Find DJI mic", v -> findMic());
        video = new VideoView(this);
        video.setBackgroundColor(0xff090909);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        video.setOnPreparedListener(mp -> { videoReady = true; seek.setMax(Math.max(1, video.getDuration())); status.setText("Video ready. Cue it, then prepare recording. Controls remain below the selected video area."); });
        video.setOnErrorListener((mp, what, extra) -> { status.setText("Cannot play this video (" + what + "). Choose a local MP4."); return true; });
        LinearLayout controls = row(root);
        add(controls, "◀ 10s", v -> { if (videoReady) video.seekTo(Math.max(0, video.getCurrentPosition() - 10000)); });
        add(controls, "Play / Pause", v -> { if (videoReady) { if (video.isPlaying()) video.pause(); else video.start(); } });
        add(controls, "10s ▶", v -> { if (videoReady) video.seekTo(Math.min(video.getDuration(), video.getCurrentPosition() + 10000)); });
        seek = new SeekBar(this); root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser && videoReady) video.seekTo(progress); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; }
        });
        time = new TextView(this); root.addView(time);
        add(root, "Prepare full-screen recorder", v -> prepare());
        add(root, "Stop recording", v -> { Intent i = new Intent(this, CaptureService.class); i.setAction(CaptureService.STOP); startService(i); status.setText("Finishing recording and crop…"); });
        setContentView(root);
        handler.post(tick);
        findMic();
    }
    private LinearLayout row(LinearLayout root) { LinearLayout r = new LinearLayout(this); root.addView(r); return r; }
    private void add(LinearLayout root, String title, View.OnClickListener click) {
        Button b = new Button(this); b.setText(title); b.setTextSize(11); b.setOnClickListener(click);
        root.addView(b, new LinearLayout.LayoutParams(root.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2, root.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0));
    }
    private void openVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/*");
        startActivityForResult(i, PICK_VIDEO);
    }
    private void findMic() {
        micId = -1;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                micId = d.getId(); status.setText("Bluetooth mic: " + d.getProductName()); return;
            }
        status.setText("Connect DJI Bluetooth mic, then tap Find DJI mic.");
    }
    private void prepare() {
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Allow Display over other apps, then return and tap Prepare again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, PERMISSIONS); return;
        }
        findMic(); if (micId < 0) return;
        MediaProjectionManager pm = getSystemService(MediaProjectionManager.class);
        // Android 14+: request the default display, without the single-app option.
        startActivityForResult(pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants);
        if (code == PERMISSIONS && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) prepare();
        else status.setText("Camera and microphone permissions are needed for face camera and DJI voice.");
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_VIDEO && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            videoReady = false; video.setVideoURI(uri); video.requestFocus();
        }
        if (code == CAPTURE && result == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class); i.setAction(CaptureService.START);
            i.putExtra("result", result); i.putExtra("data", data); i.putExtra("inputId", micId);
            if (videoReady) {
                int[] origin = new int[2]; video.getLocationOnScreen(origin);
                i.putExtra("videoBounds", new RectF(origin[0], origin[1], origin[0]+video.getWidth(), origin[1]+video.getHeight()));
            }
            startForegroundService(i);
            status.setText("Move/resize the crop and face box; cue the video; tap floating Record when ready.");
        }
    }
    @Override protected void onDestroy() { handler.removeCallbacks(tick); if (video != null) video.stopPlayback(); super.onDestroy(); }
}
