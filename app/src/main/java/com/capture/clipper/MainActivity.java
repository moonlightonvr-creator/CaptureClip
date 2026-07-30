package com.capture.clipper;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final int REQ_PERM_CAMERA = 101;
    private static final int REQ_PERM_ALL = 102;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private SurfaceView captureSurface;
    private SurfaceHolder surfaceHolder;
    private BrowserOverlayView browserSource;
    private Button btnClip, btnLoad;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;
    private ImageButton btnSettings;

    private VideoCaptureEngine captureEngine;
    private UsbManager usbManager;
    private boolean isCapturing = false;

    // Phone camera fallback
    private Camera phoneCamera;

    private SharedPreferences prefs;
    private static final String PREFS = "capture_prefs";

    private static final String PERM_CHANNEL_ID = "permissions_channel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
        } catch (Exception e) {
            try {
                File f = new File(getExternalFilesDir(null), "startup_crash.txt");
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(android.util.Log.getStackTraceString(e).getBytes());
                }
            } catch (Exception ignored) {}
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Startup crash")
                    .setMessage(android.util.Log.getStackTraceString(e))
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        captureSurface = findViewById(R.id.captureSurface);
        surfaceHolder = captureSurface.getHolder();
        surfaceHolder.addCallback(this);

        browserSource = findViewById(R.id.browserSource);
        btnClip = findViewById(R.id.btnClip);
        btnLoad = findViewById(R.id.btnLoad);
        selectQuality = findViewById(R.id.selectQuality);
        btnSettings = findViewById(R.id.btnSettings);

        inputBitrate = findViewById(R.id.inputBitrate);
        inputUrl = findViewById(R.id.inputUrl);

        // Load persisted settings
        inputBitrate.setText(String.valueOf(prefs.getInt("bitrate_kbps", 6000)));
        inputUrl.setText(prefs.getString("overlay_url", "https://google.com"));

        String[] qualityOptions = {"1280x720 @60 (720p60)", "1280x720 @30 (720p30)", "854x480 @30 (480p)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, qualityOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selectQuality.setAdapter(adapter);
        int savedQuality = prefs.getInt("quality_index", 0);
        if (savedQuality >= 0 && savedQuality < qualityOptions.length) selectQuality.setSelection(savedQuality);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Load overlay URL into WebView
        browserSource.loadUrl(inputUrl.getText().toString());

        captureEngine = new VideoCaptureEngine();
        captureEngine.setCallback(new VideoCaptureEngine.Callback() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Stream Active!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onStopped() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Stream Stopped!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + msg, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onClippaved(File file) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Clip Saved to Gallery!", Toast.LENGTH_LONG).show());
            }
        });

        btnLoad.setOnClickListener(v -> {
            String url = inputUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                browserSource.loadUrl(url);
                prefs.edit().putString("overlay_url", url).apply();
            }
        });

        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        btnClip.setOnClickListener(v -> {
            if (isCapturing) {
                captureEngine.stopCapture();
                stopPhoneCameraPreview();
                isCapturing = false;
            } else {
                Toast.makeText(MainActivity.this, "Processing buffer...", Toast.LENGTH_SHORT).show();
            }
        });

        // Ensure notification channel for permission reminders
        createPermissionNotificationChannel();

        // Check permissions on start and request if missing
        checkAndRequestPermissions();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Prefer hardware UVC if a device is connected; otherwise start phone camera preview
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList != null && !deviceList.isEmpty()) {
            startHardwareUVCStream();
        } else {
            startPhoneCameraPreview();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (captureEngine != null) {
            captureEngine.stopCapture();
        }
        stopPhoneCameraPreview();
    }

    private void startHardwareUVCStream() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) {
            Toast.makeText(this, "No USB capture device found, using phone camera.", Toast.LENGTH_SHORT).show();
            startPhoneCameraPreview();
            return;
        }

        UsbDevice device = deviceList.values().iterator().next();
        String bitrateText = inputBitrate.getText().toString().trim();
        int targetBitrate = bitrateText.isEmpty() ? 6000000 : Integer.parseInt(bitrateText) * 1000;

        try {
            captureEngine.startcapture(device, surfaceHolder.getSurface(), 1280, 720, targetBitrate);
            isCapturing = true;
        } catch (Exception e) {
            Toast.makeText(this, "Hardware Link Fault: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            startPhoneCameraPreview();
        }
    }

    private void startPhoneCameraPreview() {
        try {
            if (phoneCamera != null) return;
            phoneCamera = Camera.open();
            phoneCamera.setPreviewDisplay(surfaceHolder);
            phoneCamera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "Phone camera preview failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            phoneCamera = null;
        }
    }

    private void stopPhoneCameraPreview() {
        try {
            if (phoneCamera != null) {
                phoneCamera.stopPreview();
                phoneCamera.release();
                phoneCamera = null;
            }
        } catch (Exception ignored) {}
    }

    // Permissions handling
    private void checkAndRequestPermissions() {
        boolean missing = false;
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            // all good
            cancelPermissionNotification();
            return;
        }

        // If we should show rationale for any permission, show an explanatory dialog first
        boolean shouldShowRationale = false;
        for (String p : REQUIRED_PERMISSIONS) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, p)) { shouldShowRationale = true; break; }
        }

        if (shouldShowRationale) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissions required")
                    .setMessage("CaptureClip needs Camera, Microphone and Storage access to preview and save clips. Please allow these permissions.")
                    .setPositiveButton("Allow", (d, which) -> ActivityCompat.requestPermissions(MainActivity.this, REQUIRED_PERMISSIONS, REQ_PERM_ALL))
                    .setNegativeButton("Cancel", (d, which) -> showPermissionReminder())
                    .setCancelable(false)
                    .show();
        } else {
            // Directly request permissions
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERM_ALL);
        }
    }

    private void showPermissionReminder() {
        // Show a persistent notification so the user can come back and grant permissions
        showPermissionNotification();
        new AlertDialog.Builder(this)
                .setTitle("Permissions need granting")
                .setMessage("You can grant permissions later from the notification or app settings. The app won't be able to record until permissions are granted.")
                .setPositiveButton("Open Settings", (d, w) -> openAppSettings())
                .setNegativeButton("OK", null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_ALL) {
            boolean allGranted = true;
            for (int r : grantResults) { if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) { allGranted = false; break; } }
            if (!allGranted) {
                // Check if any permission was denied permanently
                boolean permanentlyDenied = false;
                for (String p : REQUIRED_PERMISSIONS) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, p)
                            && ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permanentlyDenied = true; break;
                    }
                }
                if (permanentlyDenied) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permissions blocked")
                            .setMessage("You have denied permissions and selected Don't ask again. Please open app settings and enable the permissions.")
                            .setPositiveButton("Open Settings", (d, w) -> openAppSettings())
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    // User dismissed or denied without 'Don't ask again' — show reminder
                    showPermissionReminder();
                }
            } else {
                cancelPermissionNotification();
                Toast.makeText(this, "Permissions granted — you can now preview and record.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void createPermissionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(PERM_CHANNEL_ID, "Permissions", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Reminder to grant app permissions");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showPermissionNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, PERM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("CaptureClip needs permissions")
                .setContentText("Tap to open app and grant Camera/Microphone/Storage permissions")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(1001, builder.build());
    }

    private void cancelPermissionNotification() {
        NotificationManagerCompat.from(this).cancel(1001);
    }
}

class BrowserOverlayView extends WebView {
    private float touchX, touchY;

    public BrowserOverlayView(Context context) { super(context); init(); }
    public BrowserOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        setWebViewClient(new WebViewClient());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchX = getX() - event.getRawX();
                touchY = getY() - event.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                animate().x(event.getRawX() + touchX).y(event.getRawY() + touchY).setDuration(0).start();
                break;
            case MotionEvent.ACTION_UP:
                // persist position
                try {
                    float x = getX();
                    float y = getY();
                    getContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                            .edit().putFloat("overlay_x", x).putFloat("overlay_y", y).apply();
                } catch (Exception ignored) {}
                break;
            default:
                return super.onTouchEvent(event);
        }
        return true;
    }
}

class VideoCaptureEngine {
    private static final int FPS = 60;
    private Surface previewSurface;
    private MediaCodec encoder;
    private volatile boolean running;
    private Thread encoderOutputThread;

    private final ConcurrentLinkedQueue<byte[]> frameBuffer = new ConcurrentLinkedQueue<>();
    private long bufferDurationUs = 30_000_000L;
    private long oldestPts, latestPts;
    private MediaFormat trackFormat;
    private int videoWidth, videoHeight, videoBitrate;
    private Callback callback;

    public interface Callback {
        void onStarted();
        void onStopped();
        void onError(String msg);
        void onClippaved(File file);
    }

    public void setCallback(Callback cb) { this.callback = cb; }

    public void startcapture(UsbDevice device, Surface surface, int width, int height, int bitrate) throws Exception {
        this.videoWidth = width;
        this.videoHeight = height;
        this.videoBitrate = bitrate;
        this.previewSurface = surface;

        setupEncoder();
        running = true;

        if (callback != null) callback.onStarted();
    }

    public void stopCapture() {
        running = false;
        if (encoderOutputThread != null) {
            try { encoderOutputThread.join(2000); } catch (Exception ignored) {}
            encoderOutputThread = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        frameBuffer.clear();
        oldestPts = latestPts = 0;
        trackFormat = null;
        if (callback != null) callback.onStopped();
    }

    private void setupEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }
}
