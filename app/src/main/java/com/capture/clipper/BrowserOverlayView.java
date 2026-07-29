package com.capture.clipper;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class BrowserOverlayView extends WebView {
    private float downX, downY, startX, startY;
    private boolean isDragging;
    private boolean dragEnabled = true;

    public BrowserOverlayView(Context context) { super(context); init(); }
    public BrowserOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    public void setDragEnabled(boolean enabled) { dragEnabled = enabled; }

    private void init() {
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        setWebViewClient(new WebViewClient());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragEnabled) return super.onTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startX = getX();
                startY = getY();
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                    isDragging = true;
                    setX(startX + dx);
                    setY(startY + dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!isDragging) {
                    MotionEvent tap = MotionEvent.obtain(event);
                    super.onTouchEvent(tap);
                    tap.recycle();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
