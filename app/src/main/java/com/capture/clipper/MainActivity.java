package com.capture.clipper;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private SurfaceView captureSurface;
    private SurfaceHolder surfaceHolder;
    private BrowserOverlayView browserSource;
    private Button btnClip, btnLoad;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;
    
    private com.capture.clipper.VideoCaptureEngine captureEngine;
    private UsbManager usbManager;
    private boolean isCapturing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        captureSurface = findViewById(R.id.captureSurface);
        surfaceHolder = captureSurface.getHolder();
        surfaceHolder.addCallback(this);

        browserSource = findViewById(R.id.browserSource);
        btnClip = findViewById(R.id.btnClip);
        btnLoad = findViewById(R.id.btnLoad);
        selectQuality = findViewById(R.id.selectQuality);
        
        inputBitrate = (EditText) findViewById(R.id.inputBitrate);
        inputUrl = (EditText) findViewById(R.id.inputUrl);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        browserSource.loadUrl("https://google.com");

        captureEngine = new com.capture.clipper.VideoCaptureEngine();
        captureEngine.setCallback(new com.capture.clipper.VideoCaptureEngine.Callback() {
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

        btnLoad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = inputUrl.getText().toString().trim();
                if (!url.isEmpty()) browserSource.loadUrl(url);
            }
        });

        btnClip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isCapturing) {
                    captureEngine.stopCapture();
                    isCapturing = false;
                } else {
                    Toast.makeText(MainActivity.this, "Processing buffer...", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startHardwareUVCStream();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (captureEngine != null) {
            captureEngine.stopCapture();
        }
    }

    private void startHardwareUVCStream() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "Connect your capture card via OTG!", Toast.LENGTH_SHORT).show();
            return;
        }

        UsbDevice device = deviceList.values().iterator().next();
        String bitrateText = inputBitrate.getText().toString().trim();
        int targetBitrate = bitrateText.isEmpty() ? 2500000 : Integer.parseInt(bitrateText) * 1000;

        try {
            // Fixed naming case step to line up with the lowercase 'c' inside your file
            captureEngine.startcapture(device, surfaceHolder.getSurface(), 1920, 1080, targetBitrate);
            isCapturing = true;
        } catch (Exception e) {
            Toast.makeText(this, "Hardware Link Fault: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
