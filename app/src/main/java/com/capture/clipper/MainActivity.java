package com.capture.clipper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class MainActivity extends AppCompatActivity
        implements View.OnClickListener, SurfaceHolder.Callback {

    private static final String ACTION_USB_PERMISSION = "com.capture.clipper.USB_PERMISSION";

    private SurfaceView captureSurface;
    private BrowserOverlayView browserOverlay;
    private Button btnClip, btnLoad, btnToggleDrag;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;
    private SeekBar durationSeek;
    private TextView statusText, durationLabel;

    private VideoCaptureEngine captureEngine;
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;
    private UsbDevice pendingDevice;
    private boolean surfaceReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        captureSurface = findViewById(R.id.captureSurface);
        browserOverlay = findViewById(R.id.browserSource);
        btnClip = findViewById(R.id.btnClip);
        btnLoad = findViewById(R.id.btnLoad);
        btnToggleDrag = findViewById(R.id.btnToggleDrag);
        selectQuality = findViewById(R.id.selectQuality);
        inputBitrate = findViewById(R.id.inputBitrate);
        inputUrl = findViewById(R.id.inputUrl);
        durationSeek = findViewById(R.id.durationSeek);
        statusText = findViewById(R.id.statusText);
        durationLabel = findViewById(R.id.durationLabel);

        btnClip.setOnClickListener(this);
        btnLoad.setOnClickListener(this);
        btnToggleDrag.setOnClickListener(this);

        durationSeek.setMax(30);
        durationLabel.setText("30s");
        durationSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean u) {
                int sec = 30 + p;
                durationLabel.setText(sec + "s");
                if (captureEngine != null)
                    captureEngine.setBufferDurationSec(sec);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        captureSurface.getHolder().addCallback(this);

        captureEngine = new VideoCaptureEngine();
        captureEngine.setCallback(new VideoCaptureEngine.Callback() {
            @Override public void onStarted() {
                runOnUiThread(() -> statusText.setText("UVC connected"));
            }
            @Override public void onStopped() {
                runOnUiThread(() -> statusText.setText("UVC disconnected"));
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    statusText.setText("Error: " + msg);
                });
            }
            @Override public void onClipSaved(File f) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Saved: " + f.getName(), Toast.LENGTH_LONG).show();
                    statusText.setText("Clipped: " + f.getName());
                });
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (device != null && granted) {
                    pendingDevice = device;
                    tryStartCapture();
                } else {
                    Toast.makeText(MainActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        };
        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        browserOverlay.loadUrl("https://www.google.com");
        btnToggleDrag.setText("DRAG ON");
    }

    private UsbDevice findUvcDevice() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getProductId() != 0 || d.getVendorId() != 0)
                return d;
        }
        return null;
    }

    private void requestPermission(UsbDevice device) {
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, pi);
    }

    private void tryStartCapture() {
        if (pendingDevice == null || !surfaceReady) return;
        stopCapture();
        try {
            int width, height;
            switch (selectQuality.getSelectedItemPosition()) {
                case 0: width = 1920; height = 1080; break;
                case 1: width = 1280; height = 720; break;
                default: width = 640; height = 480; break;
            }
            int bitrate = 2500000;
            try {
                bitrate = Integer.parseInt(inputBitrate.getText().toString().trim()) * 1000;
            } catch (NumberFormatException ignored) {}
            captureEngine.startCapture(pendingDevice,
                    captureSurface.getHolder().getSurface(), width, height, bitrate);
            pendingDevice = null;
        } catch (Exception e) {
            Toast.makeText(this, "Start failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopCapture() {
        if (captureEngine != null) captureEngine.stopCapture();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnClip) {
            File dir = getExternalFilesDir(null);
            File out = new File(dir, "Clip_" + System.currentTimeMillis() + ".mp4");
            captureEngine.saveClip(out);
            statusText.setText("Saving clip...");
        } else if (v.getId() == R.id.btnLoad) {
            String url = inputUrl.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http://") && !url.startsWith("https://"))
                    url = "https://" + url;
                browserOverlay.loadUrl(url);
            }
        } else if (v.getId() == R.id.btnToggleDrag) {
            boolean drag = !browserOverlay.isDragEnabled();
            browserOverlay.setDragEnabled(drag);
            btnToggleDrag.setText(drag ? "DRAG ON" : "DRAG OFF");
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingDevice != null) {
            tryStartCapture();
        } else {
            UsbDevice device = findUvcDevice();
            if (device == null) {
                statusText.setText("No USB device found");
                return;
            }
            pendingDevice = device;
            if (usbManager.hasPermission(device)) {
                tryStartCapture();
            } else {
                requestPermission(device);
            }
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopCapture();
    }

    @Override
    protected void onDestroy() {
        stopCapture();
        if (usbReceiver != null) unregisterReceiver(usbReceiver);
        super.onDestroy();
    }
}
