package com.blackhole.downloader;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(30), dp(22), dp(32));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text("‹", 32, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, dp(16), dp(2));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = text("DOWNLOADS", 15, Color.WHITE);
        title.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, titleParams);

        TextView clear = text("CLEAR", 10, Color.rgb(105, 105, 105));
        clear.setLetterSpacing(0.12f);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(12), 0, 0, 0);
        clear.setOnClickListener(v -> {
            HistoryStore.clear(this);
            render();
        });
        header.addView(clear, new LinearLayout.LayoutParams(dp(60), dp(44)));

        content.addView(header);
        addSpacer(content, 22);

        List<HistoryStore.Entry> entries = HistoryStore.list(this);
        if (entries.isEmpty()) {
            TextView empty = text("NOTHING DOWNLOADED YET", 11, Color.rgb(105, 105, 105));
            empty.setGravity(Gravity.CENTER);
            empty.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
            );
            content.addView(empty, emptyParams);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                content.addView(buildHistoryRow(entries.get(i)));
                if (i < entries.size() - 1) content.addView(divider());
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT
        ));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }

    private View buildHistoryRow(HistoryStore.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        TextView title = text(entry.title, 14, Color.WHITE);
        title.setMaxLines(2);
        row.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        addSpacer(row, 7);

        String details = entry.source;
        if (!entry.resolution.isEmpty()) {
            if (!details.isEmpty()) details += "  •  ";
            details += entry.resolution;
        }
        TextView source = text(details, 10, Color.rgb(132, 132, 132));
        row.addView(source);

        addSpacer(row, 5);

        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy  •  hh:mm a", Locale.getDefault());
        TextView date = text(format.format(new Date(entry.timestamp)), 9, Color.rgb(82, 82, 82));
        row.addView(date);

        return row;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(25, 25, 25));
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));
        return line;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    private void addSpacer(LinearLayout parent, int heightDp) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
