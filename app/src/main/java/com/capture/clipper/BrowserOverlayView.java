package com.capture.clipper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class BrowserOverlayView extends FrameLayout {
    private WebView webView;
    private ImageButton btnOpen, btnClose;
    private float dX, dY;

    public BrowserOverlayView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public BrowserOverlayView(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void init(Context ctx) {
        LayoutInflater.from(ctx).inflate(R.layout.view_browser_overlay, this, true);
        webView = findViewById(R.id.overlayWebView);
        btnOpen = findViewById(R.id.btnOverlayOpen);
        btnClose = findViewById(R.id.btnOverlayClose);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // load inside overlay
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        btnOpen.setOnClickListener(v -> {
            String url = webView.getUrl();
            if (url == null || url.isEmpty()) url = "https://www.google.com";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        });

        btnClose.setOnClickListener(v -> setVisibility(View.GONE));

        // dragging
        this.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.animate().x(event.getRawX() + dX).y(event.getRawY() + dY).setDuration(0).start();
                    return true;
                default:
                    return false;
            }
        });

        // long-press to copy current URL
        webView.setOnLongClickListener(v -> {
            String url = webView.getUrl();
            if (url != null && !url.isEmpty()) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Overlay URL", url);
                cm.setPrimaryClip(clip);
                Toast.makeText(getContext(), "URL copied", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
    }

    public void loadUrl(String url) {
        if (url == null || url.trim().isEmpty()) url = "https://www.google.com";
        webView.loadUrl(url);
        setVisibility(View.VISIBLE);
    }
}
