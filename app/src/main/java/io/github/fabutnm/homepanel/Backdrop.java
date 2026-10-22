package io.github.fabutnm.homepanel;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * The panel's background, like a HomeKit wallpaper seen through frosted glass: a few large soft colour glows on a
 * dark base, painted once into a small bitmap and stretched with filtering, which blurs it for free. No image files,
 * no animation, about 0.5 MB in memory.
 */
final class Backdrop {
    static final String DEFAULT = "sunset";
    static final String[] KEYS = {"sunset", "ocean", "forest", "aurora", "dusk", "graphite", "black"};
    static final String[] NAMES = {"Закат", "Океан", "Лес", "Сияние", "Сумерки", "Графит", "Чёрный"};

    /** Base colour, then glows as x, y (0..1 of the screen), radius (of the width) and colour. */
    private static final Object[][] PALETTES = {
            {0xFF1A0F24, .15f, .95f, .75f, 0xFFE8743B, .80f, .15f, .65f, 0xFFB03A6E, 1.f, .90f, .55f, 0xFF4B2E8F},
            {0xFF04121F, .10f, .10f, .70f, 0xFF1C64C8, .90f, .80f, .70f, 0xFF0E8FA8, .55f, 1.05f, .50f, 0xFF2B2A86},
            {0xFF07130D, .05f, .85f, .70f, 0xFF2C7A4B, .85f, .20f, .60f, 0xFF7E9A35, .60f, 1.05f, .50f, 0xFF16575A},
            {0xFF050816, .20f, .20f, .65f, 0xFF15B8A6, .85f, .35f, .60f, 0xFF6D35E0, .45f, 1.05f, .55f, 0xFF1E9E55},
            {0xFF0B0C1D, .20f, .90f, .70f, 0xFF3B4FB8, .85f, .10f, .60f, 0xFFB65A8E, .95f, .95f, .45f, 0xFFDA8A4A},
            {0xFF101013, .20f, .20f, .70f, 0xFF3B3B44, .85f, .85f, .70f, 0xFF2E2E36, .55f, .55f, .45f, 0xFF45454F},
            {0xFF000000},
    };

    private Backdrop() {}

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    static Drawable create(Resources res, String key) {
        int w = 480, h = 270;   // a quarter of 1080p: stretched with filtering it reads as a deep blur
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Object[] p = PALETTES[index(key)];
        canvas.drawColor((Integer) p[0]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 1; i + 3 < p.length; i += 4) {
            float x = (Float) p[i] * w, y = (Float) p[i + 1] * h, r = (Float) p[i + 2] * w;
            int c = (Integer) p[i + 3];
            paint.setShader(new RadialGradient(x, y, r, new int[]{c & 0xD0FFFFFF, c & 0x60FFFFFF, c & 0x00FFFFFF},
                    new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        // A darker top and bottom keep white text readable on any palette.
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x8C000000, 0x33000000, 0x80000000},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable d = new BitmapDrawable(res, bitmap);
        d.setFilterBitmap(true);
        return d;
    }
}
