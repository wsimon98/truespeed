package com.willsimon.retroxrealwalk;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.willsimon.retroxrealwalk.model.WalkState;
import com.willsimon.retroxrealwalk.render.RetroGLView;
import com.willsimon.retroxrealwalk.tracking.HeadPose;
import com.willsimon.retroxrealwalk.tracking.PhoneOrientationTracker;
import com.willsimon.retroxrealwalk.tracking.XrealAirTracker;

import java.util.Locale;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener, XrealAirTracker.Listener {
    private static final int BG = Color.rgb(5, 7, 10);
    private static final int PANEL = Color.rgb(15, 20, 27);
    private static final int TEXT = Color.rgb(224, 244, 247);
    private static final int MUTED = Color.rgb(137, 165, 170);
    private static final int CYAN = Color.rgb(0, 229, 255);
    private static final int GREEN = Color.rgb(94, 255, 116);
    private static final int RED = Color.rgb(255, 80, 98);

    private final WalkState walkState = new WalkState();
    private final HeadPose headPose = new HeadPose();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private DisplayManager displayManager;
    private XrealAirTracker xrealTracker;
    private PhoneOrientationTracker phoneTracker;
    private RetroPresentation presentation;
    private RetroGLView phonePreview;

    private TextView speedText;
    private TextView distanceText;
    private TextView timeText;
    private TextView glassesText;
    private TextView trackingText;
    private TextView anglesText;
    private TextView statusText;
    private Button pauseButton;
    private SharedPreferences preferences;

    private volatile boolean xrealConnected = false;
    private volatile String latestStatus = "Starting...";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        preferences = getSharedPreferences("retro_xreal_walk", MODE_PRIVATE);
        walkState.setSpeedMph(preferences.getFloat("speed_mph", 2.5f));

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        phoneTracker = new PhoneOrientationTracker(this, headPose);
        xrealTracker = new XrealAirTracker(this, headPose, this);

        setContentView(buildPhoneUi());
        refreshExternalDisplay();
        uiHandler.post(uiTick);
    }

    private View buildPhoneUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("RETRO XREAL WALK", 24, CYAN);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = text("Original XREAL Air - transparent 3DoF walking prototype", 12, MUTED);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        phonePreview = new RetroGLView(this, walkState, headPose);
        FrameLayout previewPanel = new FrameLayout(this);
        previewPanel.setBackgroundColor(Color.BLACK);
        previewPanel.addView(phonePreview, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210));
        previewParams.bottomMargin = dp(14);
        root.addView(previewPanel, previewParams);

        LinearLayout info = panel();
        glassesText = text("GLASSES DISPLAY: scanning", 14, TEXT);
        trackingText = text("TRACKING: scanning", 14, TEXT);
        anglesText = text("HEAD: Y 0.0  P 0.0  R 0.0", 12, MUTED);
        statusText = text("STATUS: Starting...", 12, MUTED);
        info.addView(glassesText, matchWrap());
        info.addView(trackingText, paddedTop(5));
        info.addView(anglesText, paddedTop(5));
        info.addView(statusText, paddedTop(8));
        root.addView(info, panelParams());

        LinearLayout stats = panel();
        speedText = text("2.5 MPH", 28, GREEN);
        speedText.setGravity(Gravity.CENTER_HORIZONTAL);
        stats.addView(speedText, matchWrap());

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.CENTER);
        speedRow.setPadding(0, dp(8), 0, dp(8));
        Button minus = button("- 0.1");
        Button plus = button("+ 0.1");
        minus.setOnClickListener(v -> adjustSpeed(-0.1));
        plus.setOnClickListener(v -> adjustSpeed(0.1));
        speedRow.addView(minus, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        plusLp.leftMargin = dp(10);
        speedRow.addView(plus, plusLp);
        stats.addView(speedRow, matchWrap());

        distanceText = text("0.000 MI", 17, TEXT);
        timeText = text("00:00", 17, TEXT);
        distanceText.setGravity(Gravity.CENTER_HORIZONTAL);
        timeText.setGravity(Gravity.CENTER_HORIZONTAL);
        stats.addView(distanceText, matchWrap());
        stats.addView(timeText, paddedTop(4));
        root.addView(stats, panelParams());

        pauseButton = button("START WALK");
        pauseButton.setTextSize(19);
        pauseButton.setTextColor(Color.BLACK);
        pauseButton.setBackgroundColor(GREEN);
        pauseButton.setOnClickListener(v -> {
            walkState.togglePaused();
            updatePauseButton();
        });
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        big.topMargin = dp(2);
        big.bottomMargin = dp(10);
        root.addView(pauseButton, big);

        Button recenter = button("RECENTER HEAD");
        recenter.setOnClickListener(v -> {
            headPose.recenter();
            latestStatus = "Head view recentered. Treadmill-forward direction is unchanged.";
        });
        root.addView(recenter, fullButtonParams());

        Button reconnect = button("RECONNECT XREAL AIR");
        reconnect.setOnClickListener(v -> xrealTracker.reconnect());
        root.addView(reconnect, fullButtonParams());

        Button reset = button("RESET SESSION");
        reset.setOnClickListener(v -> {
            walkState.reset();
            updatePauseButton();
            latestStatus = "Session reset.";
        });
        root.addView(reset, fullButtonParams());

        TextView help = text(
                "USE: Connect the XREAL Air, open this app, grant USB permission, face treadmill-forward, tap RECENTER HEAD, set the treadmill speed here, then tap START WALK. Looking around changes only your view. Walking always continues straight down the virtual path. Black areas emit no light through the XREAL optics.",
                12, MUTED);
        help.setPadding(dp(2), dp(14), dp(2), 0);
        root.addView(help, matchWrap());

        updatePauseButton();
        return scroll;
    }

    private void adjustSpeed(double delta) {
        walkState.adjustSpeed(delta);
        preferences.edit().putFloat("speed_mph", (float)walkState.getSpeedMph()).apply();
    }

    private void updatePauseButton() {
        if (pauseButton == null) return;
        if (walkState.isPaused()) {
            pauseButton.setText("START WALK");
            pauseButton.setBackgroundColor(GREEN);
        } else {
            pauseButton.setText("PAUSE WALK");
            pauseButton.setBackgroundColor(RED);
        }
    }

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            walkState.update(System.nanoTime());
            if (speedText != null) speedText.setText(String.format(Locale.US, "%.1f MPH", walkState.getSpeedMph()));
            if (distanceText != null) distanceText.setText(String.format(Locale.US, "%.3f MI", walkState.getDistanceMiles()));
            if (timeText != null) timeText.setText(formatTime(walkState.getElapsedSeconds()));
            if (glassesText != null) glassesText.setText("GLASSES DISPLAY: " + (presentation != null ? "ACTIVE" : "NOT FOUND"));
            if (trackingText != null) trackingText.setText("TRACKING: " + headPose.getSourceLabel());
            if (anglesText != null) {
                float[] e = headPose.getRelativeEulerDegrees();
                anglesText.setText(String.format(Locale.US, "HEAD: Y %+.1f  P %+.1f  R %+.1f", e[0], e[1], e[2]));
            }
            if (statusText != null) statusText.setText("STATUS: " + latestStatus);
            uiHandler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        phoneTracker.start();
        xrealTracker.start();
        displayManager.registerDisplayListener(this, uiHandler);
        if (phonePreview != null) phonePreview.onResume();
        if (presentation != null) presentation.resumeGl();
        refreshExternalDisplay();
    }

    @Override
    protected void onPause() {
        displayManager.unregisterDisplayListener(this);
        phoneTracker.stop();
        xrealTracker.stop();
        if (phonePreview != null) phonePreview.onPause();
        if (presentation != null) presentation.pauseGl();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(uiTick);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        super.onDestroy();
    }

    @Override public void onDisplayAdded(int displayId) { refreshExternalDisplay(); }
    @Override public void onDisplayRemoved(int displayId) { refreshExternalDisplay(); }
    @Override public void onDisplayChanged(int displayId) { refreshExternalDisplay(); }

    private void refreshExternalDisplay() {
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display target = displays.length > 0 ? displays[0] : null;

        if (target == null) {
            if (presentation != null) {
                presentation.dismiss();
                presentation = null;
            }
            return;
        }

        if (presentation != null && presentation.getDisplay().getDisplayId() == target.getDisplayId()) return;
        if (presentation != null) presentation.dismiss();

        try {
            presentation = new RetroPresentation(this, target, walkState, headPose);
            presentation.setOnDismissListener(dialog -> presentation = null);
            presentation.show();
            latestStatus = "XREAL/external display active. Face forward and tap RECENTER HEAD.";
        } catch (Exception e) {
            presentation = null;
            latestStatus = "External display could not be opened: " + e.getMessage();
        }
    }

    @Override
    public void onStatus(String status) {
        latestStatus = status;
    }

    @Override
    public void onConnected(boolean connected) {
        xrealConnected = connected;
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackgroundColor(PANEL);
        return layout;
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.12f);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setBackgroundColor(Color.rgb(26, 35, 45));
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams paddedTop(int valueDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(valueDp);
        return lp;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(9);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(double seconds) {
        int total = (int)Math.max(0, seconds);
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int secs = total % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private static final class RetroPresentation extends Presentation {
        private final WalkState state;
        private final HeadPose pose;
        private RetroGLView glView;

        RetroPresentation(Context outerContext, Display display, WalkState state, HeadPose pose) {
            super(outerContext, display);
            this.state = state;
            this.pose = pose;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Window window = getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            glView = new RetroGLView(getContext(), state, pose);
            setContentView(glView);
        }

        void pauseGl() { if (glView != null) glView.onPause(); }
        void resumeGl() { if (glView != null) glView.onResume(); }
    }
}
