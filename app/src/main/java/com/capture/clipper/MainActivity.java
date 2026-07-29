package com.capture.clipper;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.MotionEvent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.util.AttributeSet;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private SurfaceView captureSurface;
    private SurfaceHolder surfaceHolder;
    private BrowserOverlayView browserSource;
    private Button btnClip, btnLoad;
    private EditText inputBitrate, inputUrl;
    private Spinner selectQuality;
    
    private VideoCaptureEngine captureEngine;
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
            captureEngine.startcapture(device, surfaceHolder.getSurface(), 1920, 1080, targetBitrate);
            isCapturing = true;
        } catch (Exception e) {
            Toast.makeText(this, "Hardware Link Fault: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}

// =================================================================
// LOCAL VIDEO CAPTURE DRIVER ENGINE CLASS (Merged Natively)
// =================================================================
class VideoCaptureEngine {
    private static final int FPS = 30;
    private Surface previewSurface;
    private MediaCodec encoder;
    private volatile boolean running;
    private Thread encoderOutputThread;
    
    private final ConcurrentLinkedQueue<byte[]> frameBuffer = new ConcurrentLinkedQueue<>();
    private long bufferDurationUs = 30_000_000L;
    private long oldestPts, latestPts;
    private MediaFormat trackFormat;
    private int videoWidth, videoHeight, videoBitrate;
    private long frameIndex;
    private Callback callback;

    public interface Callback {
        void onStarted();
        void onStopped();
        void onError(String msg);
        void onClippaved(File file);
    }

    public void setCallback(Callback cb) { this.callback = cb; }
    public void setBufferDurationSec(int sec) { this.bufferDurationUs = sec * 1_000_000L; }

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
