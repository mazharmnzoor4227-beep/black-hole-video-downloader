package com.blackhole.downloader;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int STORAGE_PERMISSION_REQUEST = 41;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_HTML_CHARS = 4_000_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private BlackHoleView blackHoleView;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private String currentUrl;
    private boolean busy;
    private String pendingPermissionUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureJetBlackWindow();
        buildHomeScreen();

        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboardListener = this::detectClipboardLink;

        String shared = extractUrlFromText(getIntent().getStringExtra(Intent.EXTRA_TEXT));
        if (shared != null) setDetectedUrl(shared);
        else detectClipboardLink();
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
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void buildHomeScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        blackHoleView = new BlackHoleView(this);
        root.addView(blackHoleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        TextView history = new TextView(this);
        history.setText("HISTORY");
        history.setTextColor(Color.rgb(118, 118, 118));
        history.setTextSize(10f);
        history.setGravity(Gravity.CENTER);
        history.setLetterSpacing(0.18f);
        history.setPadding(dp(14), dp(10), dp(14), dp(10));
        history.setBackgroundColor(Color.TRANSPARENT);
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.END;
        hp.setMargins(0, 0, dp(16), dp(22));
        root.addView(history, hp);

        blackHoleView.setOnClickListener(v -> {
            if (!busy) beginFromCurrentLink();
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        }
        if (!busy) detectClipboardLink();
    }

    @Override
    protected void onPause() {
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
        }
        super.onPause();
    }

    private void detectClipboardLink() {
        if (busy || clipboardManager == null || !clipboardManager.hasPrimaryClip()) return;
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String url = extractUrlFromText(text == null ? null : text.toString());
            if (url != null) setDetectedUrl(url);
        } catch (Throwable ignored) {
            // Share-to-BLACK-HOLE remains available if a vendor ROM restricts clipboard reads.
        }
    }

    private void setDetectedUrl(String url) {
        currentUrl = url;
        blackHoleView.hideStatus();
        blackHoleView.setMode(BlackHoleView.Mode.READY);
    }

    private void beginFromCurrentLink() {
        if (currentUrl == null) detectClipboardLink();
        if (currentUrl == null) {
            Toast.makeText(this, "Copy a video link first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionUrl = currentUrl;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
            return;
        }

        resolveAndDownload(currentUrl);
    }

    private void resolveAndDownload(String pageUrl) {
        busy = true;
        blackHoleView.setMode(BlackHoleView.Mode.PROCESSING);
        blackHoleView.showStatus("ANALYZING VIDEO", sourceName(pageUrl), 2f);

        worker.execute(() -> {
            try {
                ResolvedMedia media = resolveMedia(pageUrl);
                if (media == null || media.url == null) {
                    throw new IllegalStateException("No public video stream was found");
                }
                runOnUiThread(() -> startSystemDownload(media, pageUrl));
            } catch (Throwable error) {
                runOnUiThread(() -> showFailure(error));
            }
        });
    }

    private ResolvedMedia resolveMedia(String inputUrl) throws Exception {
        if (looksLikeDirectMedia(inputUrl)) {
            return new ResolvedMedia(inputUrl, fileNameFromUrl(inputUrl), "Direct video");
        }

        HttpURLConnection connection = open(inputUrl);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("Source returned HTTP " + code);
        }

        String finalUrl = connection.getURL().toString();
        String contentType = connection.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            connection.disconnect();
            return new ResolvedMedia(finalUrl, fileNameFromUrl(finalUrl), contentType);
        }

        String html;
        try (InputStream stream = connection.getInputStream()) {
            html = readLimited(stream);
        } finally {
            connection.disconnect();
        }

        String mediaUrl = firstMatch(html,
                "(?is)<meta[^>]+(?:property|name)=[\\\"']og:video(?::url|:secure_url)?[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                "(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"']og:video(?::url|:secure_url)?[\\\"']",
                "(?is)\\\"contentUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                "(?is)\\\"playAddr\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                "(?is)\\\"downloadAddr\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                "(?is)\\\"video_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

        mediaUrl = decodeEscapedUrl(mediaUrl);
        if (mediaUrl == null || !mediaUrl.startsWith("http")) {
            throw new IllegalStateException("This source needs server extraction");
        }

        return new ResolvedMedia(mediaUrl, fileNameFromUrl(mediaUrl), "Best public stream");
    }

    private HttpURLConnection open(String value) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(value).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,video/*;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
        return c;
    }

    private String readLimited(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1 && out.length() < MAX_HTML_CHARS) {
                int allowed = Math.min(read, MAX_HTML_CHARS - out.length());
                out.append(buffer, 0, allowed);
            }
        }
        return out.toString();
    }

    private String firstMatch(String text, String... patterns) {
        if (text == null) return null;
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private String decodeEscapedUrl(String value) {
        if (value == null) return null;
        String v = value
                .replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("&#38;", "&");
        return v.trim();
    }

    private void startSystemDownload(ResolvedMedia media, String sourcePage) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Download service unavailable");

            String fileName = sanitizeFileName(media.fileName);
            if (!hasVideoExtension(fileName)) fileName += ".mp4";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(media.url));
            request.setTitle("BLACK HOLE");
            request.setDescription("Downloading video");
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "BLACK HOLE/" + fileName);
            request.addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36");

            long id = manager.enqueue(request);
            blackHoleView.setMode(BlackHoleView.Mode.DOWNLOADING);
            blackHoleView.showStatus("DOWNLOADING", media.label, 4f);
            monitorDownload(manager, id, sourcePage, media.label);
        } catch (Throwable error) {
            showFailure(error);
        }
    }

    private void monitorDownload(DownloadManager manager, long id, String sourcePage, String label) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        showFailure(new IllegalStateException("Download disappeared"));
                        return;
                    }

                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int percent = total > 0 ? (int) Math.max(4, Math.min(99, done * 100L / total)) : 18;

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        HistoryStore.add(MainActivity.this, new HistoryStore.Entry(
                                "Video", sourceName(sourcePage), label, System.currentTimeMillis()));
                        showSuccess();
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        showFailure(new IllegalStateException("Download failed (" + reason + ")"));
                        return;
                    }

                    blackHoleView.showStatus("DOWNLOADING", label + " • " + percent + "%", percent);
                    mainHandler.postDelayed(this, 650L);
                } catch (Throwable error) {
                    showFailure(error);
                }
            }
        }, 500L);
    }

    private void showSuccess() {
        busy = false;
        blackHoleView.setMode(BlackHoleView.Mode.SUCCESS);
        blackHoleView.showStatus("DOWNLOAD COMPLETE", "Saved to Downloads / BLACK HOLE", 100f);
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                blackHoleView.hideStatus();
                blackHoleView.setMode(currentUrl == null ? BlackHoleView.Mode.IDLE : BlackHoleView.Mode.READY);
            }
        }, 3000L);
    }

    private void showFailure(Throwable error) {
        busy = false;
        blackHoleView.setMode(BlackHoleView.Mode.ERROR);
        String message = error == null ? "Try another public video link" : error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "Try another public video link";
        if (message.contains("server extraction")) {
            message = "This source needs the online extractor";
        }
        blackHoleView.showStatus("DOWNLOAD FAILED", message, -1f);
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                blackHoleView.hideStatus();
                blackHoleView.setMode(currentUrl == null ? BlackHoleView.Mode.IDLE : BlackHoleView.Mode.READY);
            }
        }, 3800L);
    }

    private boolean looksLikeDirectMedia(String url) {
        String value = url.toLowerCase(Locale.ROOT);
        return value.contains(".mp4") || value.contains(".webm") || value.contains(".m4v") ||
                value.contains(".mov") || value.contains(".3gp") || value.contains(".m3u8");
    }

    private boolean hasVideoExtension(String fileName) {
        String value = fileName.toLowerCase(Locale.ROOT);
        return value.endsWith(".mp4") || value.endsWith(".webm") || value.endsWith(".m4v") ||
                value.endsWith(".mov") || value.endsWith(".3gp");
    }

    private String fileNameFromUrl(String value) {
        try {
            String path = Uri.parse(value).getLastPathSegment();
            if (path != null && !path.trim().isEmpty() && path.length() < 120) return path;
        } catch (Throwable ignored) {
        }
        return "black-hole-" + System.currentTimeMillis() + ".mp4";
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.trim().isEmpty()) value = "black-hole-" + System.currentTimeMillis();
        value = value.replaceAll("[\\\\/:*?\\\"<>|]", "_");
        if (value.length() > 100) value = value.substring(0, 100);
        return value;
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
            if (Uri.parse(value).getHost() == null) return null;
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
        if (shared != null && !busy) setDetectedUrl(shared);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingPermissionUrl != null) {
                String url = pendingPermissionUrl;
                pendingPermissionUrl = null;
                resolveAndDownload(url);
            } else {
                pendingPermissionUrl = null;
                Toast.makeText(this, "Storage permission is needed on Android 9", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ResolvedMedia {
        final String url;
        final String fileName;
        final String label;

        ResolvedMedia(String url, String fileName, String label) {
            this.url = url;
            this.fileName = fileName;
            this.label = label;
        }
    }
}
