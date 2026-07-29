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

import android.hardware.usb.UsbDevice;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.UVCCamera;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VideoCaptureEngine {
    private static final int FPS = 30;

    private UVCCamera uvcCamera;
    private MediaCodec encoder;
    private volatile boolean running;
    private Thread encoderOutputThread;

    private final ConcurrentLinkedQueue<FramePacket> frameBuffer = new ConcurrentLinkedQueue<>();
    private long bufferDurationUs = 30_000_000L;
    private long oldestPts, latestPts;
    private MediaFormat trackFormat;
    private int videoWidth, videoHeight, videoBitrate;
    private long frameIndex;

    public interface Callback {
        void onStarted();
        void onStopped();
        void onError(String msg);
        void onClipSaved(File f);
    }
    private Callback callback;

    public void setCallback(Callback cb) { callback = cb; }
    public void setBufferDurationSec(int sec) { bufferDurationUs = sec * 1_000_000L; }

    public void startCapture(UsbDevice device, Surface previewSurface,
                             int width, int height, int bitrate) throws Exception {
        videoWidth = width; videoHeight = height; videoBitrate = bitrate; frameIndex = 0;
        uvcCamera = new UVCCamera();
        uvcCamera.open(device);
        uvcCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_NV21);
        setupEncoder();
        uvcCamera.setPreviewDisplay(previewSurface);
        uvcCamera.setFrameCallback(new IFrameCallback() {
            @Override public void onFrame(ByteBuffer frame) { feedEncoder(frame); }
        }, UVCCamera.FRAME_FORMAT_NV21);
        startEncoderOutputThread();
        uvcCamera.startPreview();
        running = true;
        if (callback != null) callback.onStarted();
    }

    public void stopCapture() {
        running = false;
        if (encoderOutputThread != null) {
            try { encoderOutputThread.join(2000); } catch (Exception ignored) {}
            encoderOutputThread = null;
        }
        if (uvcCamera != null) {
            try { uvcCamera.stopPreview(); } catch (Exception ignored) {}
            uvcCamera.destroy(); uvcCamera = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            encoder.release(); encoder = null;
        }
        synchronized (frameBuffer) { frameBuffer.clear(); }
        oldestPts = latestPts = 0; trackFormat = null;
        if (callback != null) callback.onStopped();
    }

    private void setupEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private void feedEncoder(ByteBuffer frame) {
        if (encoder == null || !running) return;
        try {
            int inputIndex = encoder.dequeueInputBuffer(0);
            if (inputIndex >= 0) {
                int size = frame.remaining();
                ByteBuffer buf = encoder.getInputBuffer(inputIndex);
                buf.clear(); buf.put(frame);
                long pts = frameIndex * 1_000_000L / FPS;
                encoder.queueInputBuffer(inputIndex, 0, size, pts, 0);
                frameIndex++;
            }
        } catch (Exception e) {
            if (callback != null) callback.onError("Encoder input: " + e.getMessage());
        }
    }

    private void startEncoderOutputThread() {
        encoderOutputThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                try {
                    int index = encoder.dequeueOutputBuffer(info, 10000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackFormat = encoder.getOutputFormat();
                    } else if (index >= 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(index);
                        byte[] data = new byte[info.size];
                        buf.get(data);
                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (!isConfig) addToBuffer(data, info.presentationTimeUs);
                        encoder.releaseOutputBuffer(index, false);
                    }
                } catch (Exception e) {
                    if (running && callback != null) callback.onError("Encoder output: " + e.getMessage());
                }
            }
        }, "encoder-out");
        encoderOutputThread.setDaemon(true);
        encoderOutputThread.start();
    }

    private void addToBuffer(byte[] data, long pts) {
        synchronized (frameBuffer) {
            if (frameBuffer.isEmpty()) oldestPts = pts;
            latestPts = pts;
            frameBuffer.add(new FramePacket(data, pts));
            while (latestPts - oldestPts > bufferDurationUs && frameBuffer.size() > 1) {
                frameBuffer.poll();
                oldestPts = frameBuffer.peek().pts;
            }
        }
    }

    public void saveClip(File outputFile) {
        if (trackFormat == null || frameBuffer.isEmpty()) {
            if (callback != null) callback.onError("Nothing to clip yet");
            return;
        }
        new Thread(() -> {
            try {
                MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int trackIndex = muxer.addTrack(trackFormat);
                muxer.start();
                synchronized (frameBuffer) {
                    long basePts = frameBuffer.peek().pts;
                    for (FramePacket p : frameBuffer) {
                        ByteBuffer buf = ByteBuffer.wrap(p.data);
                        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                        bi.set(0, p.data.length, p.pts - basePts, 0);
                        muxer.writeSampleData(trackIndex, buf, bi);
                    }
                }
                muxer.stop(); muxer.release();
                if (callback != null) callback.onClipSaved(outputFile);
            } catch (Exception e) {
                if (callback != null) callback.onError("Save clip: " + e.getMessage());
            }
        }, "clip-save").start();
    }

    private static class FramePacket {
        final byte[] data; final long pts;
        FramePacket(byte[] d, long p) { data = d; pts = p; }
    }
}
