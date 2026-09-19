package com.blackhole.downloader;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.net.URI;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends Activity {
    private static final int STORAGE_PERMISSION_REQUEST = 41;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BlackHoleView blackHoleView;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private String currentUrl;
    private boolean downloading = false;
    private String pendingPermissionUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureJetBlackWindow();
        buildHomeScreen();

        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboardListener = this::detectClipboardLink;

        String shared = extractUrlFromText(getIntent().getStringExtra(Intent.EXTRA_TEXT));
        if (shared != null) {
            currentUrl = shared;
            blackHoleView.setMode(BlackHoleView.Mode.READY);
        } else {
            detectClipboardLink();
        }
    }

    private void configureJetBlackWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
    }

    private void buildHomeScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        blackHoleView = new BlackHoleView(this);
        root.addView(blackHoleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView history = new TextView(this);
        history.setText("HISTORY");
        history.setTextColor(Color.rgb(118, 118, 118));
        history.setTextSize(10f);
        history.setGravity(Gravity.CENTER);
        history.setLetterSpacing(0.18f);
        history.setPadding(dp(14), dp(10), dp(14), dp(10));
        history.setBackgroundColor(Color.TRANSPARENT);
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        FrameLayout.LayoutParams historyParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        historyParams.gravity = Gravity.BOTTOM | Gravity.END;
        historyParams.setMargins(0, 0, dp(16), dp(22));
        root.addView(history, historyParams);

        blackHoleView.setOnClickListener(v -> {
            if (!downloading) {
                beginFromCurrentLink();
            }
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        }
        if (!downloading) detectClipboardLink();
    }

    @Override
    protected void onPause() {
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
        }
        super.onPause();
    }

    private void detectClipboardLink() {
        if (downloading || clipboardManager == null || !clipboardManager.hasPrimaryClip()) return;
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String url = extractUrlFromText(text == null ? null : text.toString());
            if (url != null) {
                currentUrl = url;
                blackHoleView.hideStatus();
                blackHoleView.setMode(BlackHoleView.Mode.READY);
            }
        } catch (Throwable ignored) {
            // Clipboard privacy restrictions differ across Android builds. Share-to-app still works.
        }
    }

    private void beginFromCurrentLink() {
        if (currentUrl == null) detectClipboardLink();
        if (currentUrl == null) {
            Toast.makeText(this, "Copy a video link first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionUrl = currentUrl;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
            return;
        }

        startDownload(currentUrl);
    }

    private void startDownload(String url) {
        downloading = true;
        blackHoleView.setMode(BlackHoleView.Mode.PROCESSING);
        blackHoleView.showStatus("ANALYZING VIDEO", sourceName(url), 0f);

        final String processId = "black-hole-" + System.currentTimeMillis();

        downloadExecutor.execute(() -> {
            boolean ffmpegReady = false;
            String title = "Video";
            String resolution = "Best available";
            String fileSizeLabel = "";

            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                try {
                    FFmpeg.getInstance().init(getApplicationContext());
                    ffmpegReady = true;
                } catch (Throwable ignored) {
                    ffmpegReady = false;
                }

                String format = ffmpegReady
                        ? "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
                        : "best[ext=mp4]/best";

                try {
                    YoutubeDLRequest infoRequest = new YoutubeDLRequest(url);
                    infoRequest.addOption("--no-playlist");
                    infoRequest.addOption("-f", format);
                    VideoInfo info = YoutubeDL.getInstance().getInfo(infoRequest);
                    if (info != null) {
                        if (info.getTitle() != null && !info.getTitle().trim().isEmpty()) {
                            title = info.getTitle().trim();
                        }
                        if (info.getResolution() != null && !info.getResolution().trim().isEmpty()) {
                            resolution = info.getResolution().trim();
                        } else if (info.getHeight() > 0) {
                            resolution = info.getHeight() + "p";
                        }
                        long size = info.getFileSize() > 0 ? info.getFileSize() : info.getFileSizeApproximate();
                        if (size > 0) fileSizeLabel = formatBytes(size);
                    }
                } catch (Throwable ignored) {
                    // Metadata failure should not block a valid download.
                }

                final String readyTitle = title;
                final String readyResolution = resolution;
                final String readySize = fileSizeLabel;
                runOnUiThread(() -> {
                    blackHoleView.setMode(BlackHoleView.Mode.DOWNLOADING);
                    blackHoleView.showStatus("DOWNLOADING", buildMeta(readyResolution, readySize, 0, -1), 0f);
                });

                File downloadDir = getDownloadDirectory();
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--no-playlist");
                request.addOption("--no-mtime");
                request.addOption("-f", format);
                if (ffmpegReady) {
                    request.addOption("--merge-output-format", "mp4");
                }
                request.addOption("-o", new File(downloadDir,
                        "%(title).120B [%(id)s].%(ext)s").getAbsolutePath());

                final String finalResolution = resolution;
                final String finalSize = fileSizeLabel;
                Function3<Float, Long, String, Unit> callback = (progress, etaSeconds, line) -> {
                    int percent = progress == null ? 0 : Math.max(0, Math.min(100, Math.round(progress)));
                    long eta = etaSeconds == null ? -1L : etaSeconds;
                    runOnUiThread(() -> blackHoleView.showStatus(
                            "DOWNLOADING",
                            buildMeta(finalResolution, finalSize, percent, eta),
                            percent
                    ));
                    return Unit.INSTANCE;
                };

                YoutubeDL.getInstance().execute(request, processId, callback);

                HistoryStore.add(this, new HistoryStore.Entry(
                        readyTitle,
                        sourceName(url),
                        finalResolution,
                        System.currentTimeMillis()
                ));

                runOnUiThread(() -> showSuccess());
            } catch (Throwable error) {
                runOnUiThread(() -> showFailure(error));
            }
        });
    }

    private void showSuccess() {
        downloading = false;
        blackHoleView.setMode(BlackHoleView.Mode.SUCCESS);
        blackHoleView.showStatus("DOWNLOAD COMPLETE", "Saved to Downloads / BLACK HOLE", 100f);
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                blackHoleView.hideStatus();
                if (currentUrl != null) {
                    blackHoleView.setMode(BlackHoleView.Mode.READY);
                } else {
                    blackHoleView.setMode(BlackHoleView.Mode.IDLE);
                }
            }
        }, 3200L);
    }

    private void showFailure(Throwable error) {
        downloading = false;
        blackHoleView.setMode(BlackHoleView.Mode.ERROR);
        blackHoleView.showStatus("DOWNLOAD FAILED", friendlyError(error), -1f);
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                blackHoleView.hideStatus();
                if (currentUrl != null) blackHoleView.setMode(BlackHoleView.Mode.READY);
                else blackHoleView.setMode(BlackHoleView.Mode.IDLE);
            }
        }, 4200L);
    }

    private File getDownloadDirectory() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File folder = new File(downloads, "BLACK HOLE");
        if (!folder.exists()) folder.mkdirs();
        return folder;
    }

    private String buildMeta(String resolution, String size, int percent, long eta) {
        StringBuilder builder = new StringBuilder();
        if (resolution != null && !resolution.isEmpty()) builder.append(resolution);
        if (size != null && !size.isEmpty()) {
            if (builder.length() > 0) builder.append(" • ");
            builder.append(size);
        }
        if (percent > 0) {
            if (builder.length() > 0) builder.append(" • ");
            builder.append(percent).append('%');
        }
        if (eta >= 0 && eta < 3600) {
            if (builder.length() > 0) builder.append(" • ");
            builder.append("ETA ").append(eta).append('s');
        }
        return builder.toString();
    }

    private String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return new DecimalFormat("0.0").format(mb / 1024.0) + " GB";
        }
        return new DecimalFormat("0.0").format(mb) + " MB";
    }

    private String sourceName(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return "Video source";
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Throwable ignored) {
            return "Video source";
        }
    }

    private String friendlyError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Try another supported public video link";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unsupported url")) return "This link is not supported yet";
        if (lower.contains("private") || lower.contains("login")) return "This video may require account access";
        if (lower.contains("network") || lower.contains("timed out") || lower.contains("connection")) {
            return "Check your internet connection and try again";
        }
        return "Try again or copy another public video link";
    }

    private String extractUrlFromText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group();
        while (value.endsWith(")") || value.endsWith("]") || value.endsWith("}") ||
                value.endsWith(",") || value.endsWith(".") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            Uri uri = Uri.parse(value);
            if (uri.getHost() == null) return null;
        } catch (Throwable ignored) {
            return null;
        }
        return value;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String shared = extractUrlFromText(intent.getStringExtra(Intent.EXTRA_TEXT));
        if (shared != null && !downloading) {
            currentUrl = shared;
            blackHoleView.hideStatus();
            blackHoleView.setMode(BlackHoleView.Mode.READY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingPermissionUrl != null) {
                String url = pendingPermissionUrl;
                pendingPermissionUrl = null;
                startDownload(url);
            } else {
                pendingPermissionUrl = null;
                Toast.makeText(this, "Storage permission is needed on Android 9/10", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        downloadExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
