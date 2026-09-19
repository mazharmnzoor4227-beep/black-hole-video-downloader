package com.blackhole.downloader;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
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

    // Prefer the highest MP4 video and M4A audio that Android can merge natively.
    // This keeps the APK much smaller than bundling a full FFmpeg binary.
    private static final String VIDEO_FORMAT =
            "bestvideo[ext=mp4][vcodec^=avc1]/bestvideo[ext=mp4]";
    private static final String AUDIO_FORMAT =
            "bestaudio[ext=m4a][acodec^=mp4a]/bestaudio[ext=m4a]";
    private static final String COMBINED_FORMAT =
            "best[ext=mp4][vcodec!=none][acodec!=none]/best[ext=mp4]";

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

        // IMPORTANT: no downloader/native library is initialized here.
        // The UI opens first on every supported device; extractor setup only starts after a tap.
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
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
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
            if (!downloading) beginFromCurrentLink();
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
            // Some Android versions restrict clipboard access. Share-to-BLACK-HOLE still works.
        }
    }

    private void beginFromCurrentLink() {
        if (currentUrl == null) detectClipboardLink();
        if (currentUrl == null) {
            Toast.makeText(this, "Copy a video link first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Only Android 9 needs legacy write permission. Android 10+ uses MediaStore.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
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

        downloadExecutor.execute(() -> {
            File workDir = null;
            try {
                // Init is intentionally deferred until the user actually downloads.
                YoutubeDL.getInstance().init(getApplicationContext());

                workDir = createWorkDirectory();
                String title = "Video";
                String resolution = "Highest available";

                try {
                    YoutubeDLRequest infoRequest = new YoutubeDLRequest(url);
                    infoRequest.addOption("--no-playlist");
                    infoRequest.addOption("-f", VIDEO_FORMAT);
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
                    }
                } catch (Throwable ignored) {
                    // Metadata is optional; download can still continue.
                }

                final String displayResolution = resolution;
                runOnUiThread(() -> {
                    blackHoleView.setMode(BlackHoleView.Mode.DOWNLOADING);
                    blackHoleView.showStatus("DOWNLOADING", displayResolution, 1f);
                });

                File finalVideo;
                try {
                    finalVideo = downloadAndNativeMux(url, workDir, displayResolution);
                } catch (Throwable highQualityError) {
                    // If a site does not expose separate MP4/M4A streams, use its best combined MP4.
                    finalVideo = downloadCombinedFallback(url, workDir, displayResolution);
                }

                if (finalVideo == null || !finalVideo.exists() || finalVideo.length() <= 0) {
                    throw new IOException("Downloaded file was not created");
                }

                runOnUiThread(() -> blackHoleView.showStatus(
                        "SAVING VIDEO", displayResolution + " • 96%", 96f
                ));

                String outputName = makeOutputName(title);
                publishVideo(finalVideo, outputName);

                HistoryStore.add(this, new HistoryStore.Entry(
                        title,
                        sourceName(url),
                        displayResolution,
                        System.currentTimeMillis()
                ));

                runOnUiThread(this::showSuccess);
            } catch (Throwable error) {
                runOnUiThread(() -> showFailure(error));
            } finally {
                if (workDir != null) deleteRecursively(workDir);
            }
        });
    }

    private File downloadAndNativeMux(String url, File workDir, String resolution) throws Exception {
        File videoFile = new File(workDir, "video.mp4");
        File audioFile = new File(workDir, "audio.m4a");
        File mergedFile = new File(workDir, "merged.mp4");

        YoutubeDLRequest videoRequest = new YoutubeDLRequest(url);
        videoRequest.addOption("--no-playlist");
        videoRequest.addOption("--no-mtime");
        videoRequest.addOption("--no-part");
        videoRequest.addOption("-f", VIDEO_FORMAT);
        videoRequest.addOption("-o", videoFile.getAbsolutePath());

        Function3<Float, Long, String, Unit> videoCallback = (progress, eta, line) -> {
            float p = progress == null ? 0f : Math.max(0f, Math.min(100f, progress));
            float overall = 2f + p * 0.68f;
            runOnUiThread(() -> blackHoleView.showStatus(
                    "DOWNLOADING VIDEO", resolution + " • " + Math.round(overall) + "%", overall
            ));
            return Unit.INSTANCE;
        };
        YoutubeDL.getInstance().execute(videoRequest, "bh-video-" + System.nanoTime(), videoCallback);

        if (!videoFile.exists() || videoFile.length() == 0) {
            throw new IOException("High-quality video stream unavailable");
        }

        YoutubeDLRequest audioRequest = new YoutubeDLRequest(url);
        audioRequest.addOption("--no-playlist");
        audioRequest.addOption("--no-mtime");
        audioRequest.addOption("--no-part");
        audioRequest.addOption("-f", AUDIO_FORMAT);
        audioRequest.addOption("-o", audioFile.getAbsolutePath());

        Function3<Float, Long, String, Unit> audioCallback = (progress, eta, line) -> {
            float p = progress == null ? 0f : Math.max(0f, Math.min(100f, progress));
            float overall = 70f + p * 0.16f;
            runOnUiThread(() -> blackHoleView.showStatus(
                    "DOWNLOADING AUDIO", resolution + " • " + Math.round(overall) + "%", overall
            ));
            return Unit.INSTANCE;
        };
        YoutubeDL.getInstance().execute(audioRequest, "bh-audio-" + System.nanoTime(), audioCallback);

        if (!audioFile.exists() || audioFile.length() == 0) {
            throw new IOException("Audio stream unavailable");
        }

        runOnUiThread(() -> blackHoleView.showStatus(
                "MERGING", resolution + " • 88%", 88f
        ));

        muxMp4(videoFile, audioFile, mergedFile);
        if (!mergedFile.exists() || mergedFile.length() == 0) {
            throw new IOException("Could not merge video and audio");
        }
        return mergedFile;
    }

    private File downloadCombinedFallback(String url, File workDir, String resolution) throws Exception {
        File combined = new File(workDir, "combined.mp4");
        if (combined.exists()) combined.delete();

        runOnUiThread(() -> blackHoleView.showStatus(
                "DOWNLOADING", resolution + " • compatible mode", 4f
        ));

        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-part");
        request.addOption("-f", COMBINED_FORMAT);
        request.addOption("-o", combined.getAbsolutePath());

        Function3<Float, Long, String, Unit> callback = (progress, eta, line) -> {
            float p = progress == null ? 0f : Math.max(0f, Math.min(100f, progress));
            float overall = 5f + p * 0.88f;
            runOnUiThread(() -> blackHoleView.showStatus(
                    "DOWNLOADING", resolution + " • " + Math.round(overall) + "%", overall
            ));
            return Unit.INSTANCE;
        };

        YoutubeDL.getInstance().execute(request, "bh-combined-" + System.nanoTime(), callback);
        if (!combined.exists() || combined.length() == 0) {
            throw new IOException("No compatible MP4 stream found");
        }
        return combined;
    }

    private void muxMp4(File videoFile, File audioFile, File outputFile) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;

        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            int videoTrack = findTrack(videoExtractor, "video/");
            int audioTrack = findTrack(audioExtractor, "audio/");
            if (videoTrack < 0 || audioTrack < 0) {
                throw new IOException("Required media tracks not found");
            }

            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

            if (outputFile.exists()) outputFile.delete();
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideoTrack = muxer.addTrack(videoFormat);
            int muxAudioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            copyTrack(videoExtractor, videoTrack, muxer, muxVideoTrack);
            copyTrack(audioExtractor, audioTrack, muxer, muxAudioTrack);
        } finally {
            try {
                if (muxer != null && muxerStarted) muxer.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private void copyTrack(MediaExtractor extractor, int sourceTrack, MediaMuxer muxer, int targetTrack)
            throws IOException {
        extractor.selectTrack(sourceTrack);
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            buffer.clear();
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) break;

            long sampleTime = extractor.getSampleTime();
            if (sampleTime < 0) break;

            info.offset = 0;
            info.size = sampleSize;
            info.presentationTimeUs = sampleTime;
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(targetTrack, buffer, info);
            extractor.advance();
        }
        extractor.unselectTrack(sourceTrack);
    }

    private File createWorkDirectory() throws IOException {
        File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = getCacheDir();
        File dir = new File(base, "black-hole-" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create temporary folder");
        }
        return dir;
    }

    private void publishVideo(File source, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/BLACK HOLE");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create Downloads entry");

            boolean success = false;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open Downloads output");
                copy(input, output);
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
        } else {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File folder = new File(downloads, "BLACK HOLE");
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Could not create Downloads/BLACK HOLE");
            }
            File destination = uniqueFile(folder, displayName);
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(destination)) {
                copy(input, output);
            }
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{destination.getAbsolutePath()},
                    new String[]{"video/mp4"},
                    null
            );
        }
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private File uniqueFile(File folder, String name) {
        File candidate = new File(folder, name);
        if (!candidate.exists()) return candidate;

        String base = name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                ? name.substring(0, name.length() - 4)
                : name;
        int i = 2;
        while (candidate.exists()) {
            candidate = new File(folder, base + " (" + i + ").mp4");
            i++;
        }
        return candidate;
    }

    private String makeOutputName(String title) {
        String safe = title == null ? "Video" : title.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "Video";
        if (safe.length() > 90) safe = safe.substring(0, 90).trim();
        return safe + ".mp4";
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private void showSuccess() {
        downloading = false;
        blackHoleView.setMode(BlackHoleView.Mode.SUCCESS);
        blackHoleView.showStatus("DOWNLOAD COMPLETE", "Saved to Downloads / BLACK HOLE", 100f);
        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                blackHoleView.hideStatus();
                blackHoleView.setMode(currentUrl != null ? BlackHoleView.Mode.READY : BlackHoleView.Mode.IDLE);
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
                blackHoleView.setMode(currentUrl != null ? BlackHoleView.Mode.READY : BlackHoleView.Mode.IDLE);
            }
        }, 4200L);
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
        if (lower.contains("private") || lower.contains("login") || lower.contains("cookies")) {
            return "This video may require account access";
        }
        if (lower.contains("network") || lower.contains("timed out") || lower.contains("connection")) {
            return "Check your internet connection and try again";
        }
        if (lower.contains("permission")) return "Storage permission is required on this device";
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
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    pendingPermissionUrl != null) {
                String url = pendingPermissionUrl;
                pendingPermissionUrl = null;
                startDownload(url);
            } else {
                pendingPermissionUrl = null;
                Toast.makeText(this, "Storage permission is needed on Android 9", Toast.LENGTH_LONG).show();
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
