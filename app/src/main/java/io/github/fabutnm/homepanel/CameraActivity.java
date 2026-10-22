package io.github.fabutnm.homepanel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One camera, full screen: Home Assistant's camera_proxy snapshot, fetched again every {@link #REFRESH_MS} while
 * this screen is visible and never otherwise. Back closes it.
 */
public final class CameraActivity extends Activity {
    static final String EXTRA_ENTITY = "entity";
    static final String EXTRA_TITLE = "title";
    private static final long REFRESH_MS = 2_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::fetch;
    private PanelConfig config;
    private String entity;
    private ImageView image;
    private TextView caption;
    private boolean visible;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        entity = getIntent().getStringExtra(EXTRA_ENTITY);
        try {
            config = PanelConfig.read(getFilesDir());
        } catch (Exception e) {
            config = null;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        caption = new TextView(this);
        caption.setTextColor(0xFFFFFFFF);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        caption.setShadowLayer(8, 0, 0, 0xFF000000);
        int pad = Math.round(32 * getResources().getDisplayMetrics().density);
        caption.setPadding(pad, pad, pad, pad);
        caption.setText(getIntent().getStringExtra(EXTRA_TITLE));
        root.addView(caption, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);
        if (config == null || entity == null) finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        fetch();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void fetch() {
        main.removeCallbacks(tick);
        if (!visible || io.isShutdown()) return;
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = snapshot();
            } catch (IOException ignored) {
                // shown as "no picture" below and tried again
            }
            Bitmap result = bitmap;
            main.post(() -> {
                if (isDestroyed()) return;
                if (result != null) image.setImageBitmap(result);
                else if (image.getDrawable() == null) caption.setText(R.string.camera_failed);
                if (visible) main.postDelayed(tick, REFRESH_MS);
            });
        });
    }

    private Bitmap snapshot() throws IOException {
        URL url = new URL(config.base + "/api/camera_proxy/" + entity);
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(5_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Authorization", "Bearer " + config.token);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; a snapshot needs no alpha
                return BitmapFactory.decodeStream(in, null, o);
            }
        } finally {
            c.disconnect();
        }
    }
}
