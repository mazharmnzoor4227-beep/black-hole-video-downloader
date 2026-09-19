package com.blackhole.downloader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class BlackHoleView extends View {
    public enum Mode {
        IDLE,
        READY,
        PROCESSING,
        DOWNLOADING,
        SUCCESS,
        ERROR
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Bitmap blackHoleBitmap;
    private ValueAnimator animator;
    private float rotationDegrees = 0f;
    private float pulse = 0f;
    private Mode mode = Mode.IDLE;

    private String statusText = "";
    private String metaText = "";
    private float progress = -1f;
    private boolean statusVisible = false;

    public BlackHoleView(Context context) {
        this(context, null);
    }

    public BlackHoleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        setClickable(true);

        blackHoleBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.black_hole);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(12));
        textPaint.setLetterSpacing(0.12f);

        secondaryPaint.setColor(Color.rgb(135, 135, 135));
        secondaryPaint.setTextAlign(Paint.Align.CENTER);
        secondaryPaint.setTextSize(dp(10));
        secondaryPaint.setLetterSpacing(0.05f);

        progressTrackPaint.setColor(Color.rgb(24, 24, 24));
        progressTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        progressTrackPaint.setStrokeWidth(dp(2));

        progressFillPaint.setColor(Color.WHITE);
        progressFillPaint.setStrokeCap(Paint.Cap.ROUND);
        progressFillPaint.setStrokeWidth(dp(2));
    }

    public void setMode(Mode newMode) {
        if (newMode == mode) return;
        mode = newMode;

        if (newMode == Mode.IDLE || newMode == Mode.SUCCESS || newMode == Mode.ERROR) {
            stopAnimation();
            if (newMode == Mode.IDLE) {
                rotationDegrees = 0f;
                pulse = 0f;
            }
        } else if (newMode == Mode.READY) {
            startAnimation(7000L, 0.012f);
        } else if (newMode == Mode.PROCESSING) {
            startAnimation(3900L, 0.018f);
        } else if (newMode == Mode.DOWNLOADING) {
            startAnimation(2300L, 0.022f);
        }
        invalidate();
    }

    public Mode getMode() {
        return mode;
    }

    public void showStatus(String status, String meta, float progressPercent) {
        statusText = status == null ? "" : status;
        metaText = meta == null ? "" : meta;
        progress = Math.max(-1f, Math.min(100f, progressPercent));
        statusVisible = true;
        invalidate();
    }

    public void hideStatus() {
        statusVisible = false;
        progress = -1f;
        statusText = "";
        metaText = "";
        invalidate();
    }

    private void startAnimation(long duration, float pulseAmount) {
        stopAnimation();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            rotationDegrees = value * 360f;
            pulse = (float) Math.sin(value * Math.PI * 2.0) * pulseAmount;
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        pulse = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || blackHoleBitmap == null) return;

        float imageSize = Math.min(width * 0.98f, height * 0.66f);
        float centerX = width / 2f;
        float centerY = height * 0.47f;
        float scaledSize = imageSize * (1f + pulse);

        RectF destination = new RectF(
                centerX - scaledSize / 2f,
                centerY - scaledSize / 2f,
                centerX + scaledSize / 2f,
                centerY + scaledSize / 2f
        );

        canvas.save();
        canvas.rotate(rotationDegrees, centerX, centerY);
        canvas.drawBitmap(blackHoleBitmap, null, destination, bitmapPaint);
        canvas.restore();

        if (statusVisible) {
            drawStatus(canvas, width, height, centerY, imageSize);
        }
    }

    private void drawStatus(Canvas canvas, int width, int height, float centerY, float imageSize) {
        float infoY = Math.min(height - dp(105), centerY + imageSize * 0.36f);
        float barWidth = Math.min(width * 0.50f, dp(250));
        float left = width / 2f - barWidth / 2f;
        float right = width / 2f + barWidth / 2f;

        if (progress >= 0f) {
            canvas.drawLine(left, infoY, right, infoY, progressTrackPaint);
            float filledRight = left + (barWidth * (progress / 100f));
            canvas.drawLine(left, infoY, filledRight, infoY, progressFillPaint);
            infoY += dp(24);
        }

        if (!statusText.isEmpty()) {
            canvas.drawText(statusText, width / 2f, infoY, textPaint);
            infoY += dp(19);
        }

        if (!metaText.isEmpty()) {
            canvas.drawText(ellipsize(metaText, 54), width / 2f, infoY, secondaryPaint);
        }
    }

    private String ellipsize(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
