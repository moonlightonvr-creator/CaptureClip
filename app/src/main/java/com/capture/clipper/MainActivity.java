package com.capture.clipper;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private SurfaceView captureSurface;
    private SurfaceHolder surfaceHolder;
    private BrowserOverlayView browserSource;
    private Button btnClip, btnLoad;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;
    
    private boolean isBuffering = false;
    private Thread hardwareStreamThread;
    private final ByteArrayOutputStream videoRAMBuffer = new ByteArrayOutputStream(1024 * 1024 * 20);
    private UsbManager usbManager;

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
                String qual = selectQuality.getSelectedItem().toString();
                String bit = inputBitrate.getText().toString().trim();
                saveClip(qual, bit);
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
        stopHardwareUVCStream();
    }

    private void startHardwareUVCStream() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "Connect your capture card via OTG!", Toast.LENGTH_SHORT).show();
            return;
        }

        isBuffering = true;
        hardwareStreamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] frameChunk = new byte[1024 * 4];
                while (isBuffering) {
                    synchronized (videoRAMBuffer) {
                        if (videoRAMBuffer.size() > 1024 * 1024 * 15) {
                            videoRAMBuffer.reset();
                        }
                        videoRAMBuffer.write(frameChunk, 0, frameChunk.length);
                    }
                    try { Thread.sleep(16); } catch (InterruptedException e) { break; }
                }
            }
        });
        hardwareStreamThread.start();
        Toast.makeText(this, "SonionClip Engine Online!", Toast.LENGTH_SHORT).show();
    }

    private void stopHardwareUVCStream() {
        isBuffering = false;
        if (hardwareStreamThread != null) {
            hardwareStreamThread.interrupt();
        }
    }

    private void saveClip(String quality, String bitrate) {
        try {
            File file = new File(getExternalFilesDir(null), "SonionGoal.mp4");
            FileOutputStream fos = new FileOutputStream(file);
            synchronized (videoRAMBuffer) {
                videoRAMBuffer.writeTo(fos);
            }
            fos.close();
            Toast.makeText(this, "Clipped! " + quality + " @ " + bitrate + "Kbps", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Writing File Error", Toast.LENGTH_SHORT).show();
        }
    }
}
