package com.capture.clipper;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity implements android.view.SurfaceHolder.Callback {
    private TextView tvCameraStatus;
    private Button btnUseUvc;
    private ImageButton btnSettings;

    private android.view.SurfaceView captureSurface;
    private BrowserOverlayView browserSource;
    private android.widget.Button btnLoad, btnClip;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;

    private UsbManager usbManager;
    private boolean isUvcActive = false;

    public static final String PREFS = "capture_prefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvCameraStatus = findViewById(R.id.tvCameraStatus);
        btnUseUvc = findViewById(R.id.btnUseUvc);
        btnSettings = findViewById(R.id.btnSettings);

        captureSurface = findViewById(R.id.captureSurface);
        browserSource = findViewById(R.id.browserSource);

        btnLoad = findViewById(R.id.btnLoad);
        btnClip = findViewById(R.id.btnClip);
        inputBitrate = findViewById(R.id.inputBitrate);
        inputUrl = findViewById(R.id.inputUrl);
        selectQuality = findViewById(R.id.selectQuality);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        updateCameraStatus("Phone Camera (idle)");

        btnUseUvc.setOnClickListener(v -> attemptUvcStart());

        btnLoad.setOnClickListener(v -> {
            String url = inputUrl.getText().toString().trim();
            browserSource.loadUrl(url);
        });

        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        btnClip.setOnClickListener(v -> Toast.makeText(this, "Clip request queued (buffering)...", Toast.LENGTH_SHORT).show());
    }

    private void updateCameraStatus(String txt) {
        tvCameraStatus.setText("Camera: " + txt);
    }

    private void attemptUvcStart() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No UVC device found")
                    .setMessage("No USB capture device detected. Please plug in a UVC device and try again.")
                    .setPositiveButton("OK", null)
                    .show();
            updateCameraStatus("Phone Camera (no UVC)");
            return;
        }

        // Show device list for diagnostics
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices.values()) {
            sb.append(String.format("Vendor: 0x%04x Model: 0x%04x\n", d.getVendorId(), d.getProductId()));
            sb.append("Name: ").append(d.getDeviceName()).append('\n');
            sb.append("----------------\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("UVC devices")
                .setMessage(sb.toString())
                .setPositiveButton("Attempt start", (dialog, which) -> {
                    // For now we don't have a full UVC implementation; show diagnostic success and flip status.
                    isUvcActive = true; // optimistic
                    updateCameraStatus("UVC (active - diagnostic mode)");
                    Toast.makeText(this, "Attempting UVC stream (diagnostic). If this fails, check permissions.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void surfaceCreated(android.view.SurfaceHolder holder) {
        // no-op: preview will be controlled by user actions
    }

    @Override
    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
}
