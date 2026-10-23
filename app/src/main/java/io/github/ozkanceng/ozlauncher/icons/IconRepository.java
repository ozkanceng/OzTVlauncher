package io.github.ozkanceng.ozlauncher.icons;

import android.annotation.SuppressLint;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import io.github.ozkanceng.ozlauncher.model.LaunchItem;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Memory-bounded icon loader with private custom-icon storage and no network access. */
public final class IconRepository {
    public interface Callback { void onIcon(String itemId, Bitmap bitmap); }

    private static final String INDEX_KEY = "custom_icon_index";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory;
    private final File customDir;
    private final SharedPreferences indexPrefs;

    public IconRepository(Context context) {
        this.context = context.getApplicationContext();
        int maxKb = Math.min(8 * 1024, Math.max(2 * 1024,
                (int) (Runtime.getRuntime().maxMemory() / 1024 / 24)));
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount() / 1024; }
        };
        customDir = new File(context.getFilesDir(), "custom-icons");
        if (!customDir.exists()) customDir.mkdirs();
        indexPrefs = context.getSharedPreferences("ozlauncher_icons", Context.MODE_PRIVATE);
    }

    public Bitmap cached(String id) { return memory.get(id); }

    public void request(LaunchItem item, int sizePx, Callback callback) {
        Bitmap cached = memory.get(item.id);
        if (cached != null) { callback.onIcon(item.id, cached); return; }
        executor.execute(() -> {
            Bitmap bitmap = loadCustom(item.id, sizePx);
            if (bitmap == null) {
                if (item.kind == LaunchItem.Kind.TV_INPUT) {
                    bitmap = drawInput(item, sizePx);
                } else if (item.kind == LaunchItem.Kind.ACTION) {
                    bitmap = drawAction(item, sizePx);
                } else {
                    bitmap = loadApplication(item, sizePx);
                }
            }
            if (bitmap != null) memory.put(item.id, bitmap);
            Bitmap delivered = bitmap;
            main.post(() -> callback.onIcon(item.id, delivered));
        });
    }

    @SuppressLint("ApplySharedPref")
    public boolean saveCustom(String itemId, Uri uri) {
        Bitmap source = null;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            source = BitmapFactory.decodeStream(in);
            if (source == null) return false;
            Bitmap normalized = fit(source, 256);
            String fileName = sha256(itemId) + ".png";
            File temp = new File(customDir, fileName + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
                out.getFD().sync();
            }
            File target = new File(customDir, fileName);
            File previous = new File(customDir, fileName + ".previous");
            if (previous.exists()) previous.delete();
            if (target.exists() && !target.renameTo(previous)) return false;
            if (!temp.renameTo(target)) {
                previous.renameTo(target);
                return false;
            }
            previous.delete();
            JSONObject index = index();
            index.put(itemId, fileName);
            indexPrefs.edit().putString(INDEX_KEY, index.toString()).commit();
            memory.remove(itemId);
            if (normalized != source) normalized.recycle();
            return true;
        } catch (IOException | JSONException | SecurityException ignored) {
            return false;
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
        }
    }

    public void resetCustom(String itemId) {
        JSONObject index = index();
        String fileName = index.optString(itemId, "");
        if (!fileName.isEmpty()) new File(customDir, fileName).delete();
        index.remove(itemId);
        indexPrefs.edit().putString(INDEX_KEY, index.toString()).apply();
        memory.remove(itemId);
    }

    public Map<String, File> customFiles() {
        LinkedHashMap<String, File> result = new LinkedHashMap<>();
        JSONObject index = index();
        Iterator<String> keys = index.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            File file = new File(customDir, index.optString(id));
            if (file.isFile()) result.put(id, file);
        }
        return result;
    }

    public Map<String, byte[]> customFileBytes() {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, File> entry : customFiles().entrySet()) {
            try { result.put(entry.getKey(), java.nio.file.Files.readAllBytes(entry.getValue().toPath())); }
            catch (IOException ignored) { }
        }
        return result;
    }

    @SuppressLint("ApplySharedPref")
    public boolean replaceCustomFiles(Map<String, byte[]> icons) {
        File stage = new File(context.getCacheDir(), "restore-icons");
        File previous = new File(context.getFilesDir(), "custom-icons.previous");
        deleteTree(stage);
        deleteTree(previous);
        if (!stage.mkdirs()) return false;
        JSONObject next = new JSONObject();
        String oldIndex = indexPrefs.getString(INDEX_KEY, "{}");
        try {
            for (Map.Entry<String, byte[]> entry : icons.entrySet()) {
                if (entry.getKey().length() > 512 || entry.getValue().length > 1024 * 1024) return false;
                Bitmap decoded = BitmapFactory.decodeByteArray(entry.getValue(), 0, entry.getValue().length);
                if (decoded == null) return false;
                decoded.recycle();
                String fileName = sha256(entry.getKey()) + ".png";
                try (FileOutputStream out = new FileOutputStream(new File(stage, fileName))) {
                    out.write(entry.getValue());
                }
                next.put(entry.getKey(), fileName);
            }
            if (customDir.exists() && !customDir.renameTo(previous)) return false;
            if (!stage.renameTo(customDir)) {
                previous.renameTo(customDir);
                return false;
            }
            if (!indexPrefs.edit().putString(INDEX_KEY, next.toString()).commit()) {
                deleteTree(customDir);
                previous.renameTo(customDir);
                indexPrefs.edit().putString(INDEX_KEY, oldIndex).commit();
                return false;
            }
            deleteTree(previous);
            memory.evictAll();
            return true;
        } catch (IOException | JSONException ignored) {
            return false;
        } finally {
            if (stage.exists()) deleteTree(stage);
            if (previous.exists() && customDir.exists()) deleteTree(previous);
        }
    }

    public void shutdown() { executor.shutdownNow(); memory.evictAll(); }

    private Bitmap loadCustom(String id, int sizePx) {
        String name = index().optString(id, "");
        if (name.isEmpty()) return null;
        Bitmap decoded = BitmapFactory.decodeFile(new File(customDir, name).getAbsolutePath());
        if (decoded == null) return null;
        Bitmap result = fit(decoded, sizePx);
        if (result != decoded) decoded.recycle();
        return result;
    }

    private Bitmap loadApplication(LaunchItem item, int sizePx) {
        try {
            Drawable drawable = null;
            boolean isBanner = false;
            PackageManager pm = context.getPackageManager();
            if (item.resolveInfo != null && item.resolveInfo.activityInfo != null) {
                drawable = item.resolveInfo.activityInfo.loadBanner(pm);
                if (drawable == null && item.resolveInfo.activityInfo.applicationInfo != null) {
                    drawable = item.resolveInfo.activityInfo.applicationInfo.loadBanner(pm);
                }
                if (drawable == null) {
                    drawable = item.resolveInfo.activityInfo.loadLogo(pm);
                }
                if (drawable == null && item.resolveInfo.activityInfo.applicationInfo != null) {
                    drawable = item.resolveInfo.activityInfo.applicationInfo.loadLogo(pm);
                }
                isBanner = drawable != null;
            }
            if (drawable == null && item.resolveInfo != null) {
                drawable = item.resolveInfo.loadIcon(pm);
            }
            if (drawable == null) return null;

            int heightPx = Math.max(1, Math.round(sizePx * 9f / 16f));
            Bitmap out = Bitmap.createBitmap(sizePx, heightPx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);

            if (isBanner) {
                // TV Banners fill the entire 16:9 card edge-to-edge
                drawable.setBounds(0, 0, sizePx, heightPx);
                drawable.draw(canvas);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable aid = (AdaptiveIconDrawable) drawable;
                Drawable bg = aid.getBackground();
                Drawable fg = aid.getForeground();
                if (bg != null) {
                    bg.setBounds(0, 0, sizePx, heightPx);
                    bg.draw(canvas);
                } else {
                    drawCardBackground(canvas, sizePx, heightPx);
                }
                int iconSize = Math.round(heightPx * 0.72f);
                int left = (sizePx - iconSize) / 2;
                int top = (heightPx - iconSize) / 2;
                if (fg != null) {
                    fg.setBounds(left, top, left + iconSize, top + iconSize);
                    fg.draw(canvas);
                }
            } else {
                drawCardBackground(canvas, sizePx, heightPx);
                int iconSize = Math.round(heightPx * 0.72f);
                int left = (sizePx - iconSize) / 2;
                int top = (heightPx - iconSize) / 2;
                drawable.setBounds(left, top, left + iconSize, top + iconSize);
                drawable.draw(canvas);
            }
            return out;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void drawCardBackground(Canvas canvas, int width, int height) {
        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setShader(new LinearGradient(0, 0, width, height,
                new int[] { 0xFF242C3D, 0xFF141926, 0xFF0C0F17 }, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, cardPaint);

        float cx = width / 2f;
        float cy = height / 2f;
        cardPaint.setShader(new RadialGradient(cx, cy, height * 0.45f,
                new int[] { 0x3338BDF8, 0x00000000 }, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, height * 0.45f, cardPaint);
    }

    private static Bitmap drawAction(LaunchItem item, int width) {
        int height = Math.max(1, Math.round(width * 9f / 16f));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Electric ocean gradient (Royal Blue to Emerald Teal)
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[] { 0xFF1D4ED8, 0xFF0284C7, 0xFF0D9488 },
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        // Ambient radial light in screen center
        float cx = width / 2f;
        float cy = height * 0.42f;
        paint.setShader(new RadialGradient(cx, cy, height * 0.45f,
                new int[] { 0x4467E8F9, 0x1067E8F9, 0x00000000 }, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, height * 0.45f, paint);
        paint.setShader(null);

        // Floating modern TV display outline
        paint.setColor(0xF5FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, height * 0.035f));
        float tvW = width * 0.36f;
        float tvH = height * 0.38f;
        float tvLeft = (width - tvW) / 2f;
        float tvTop = height * 0.15f;
        canvas.drawRoundRect(new RectF(tvLeft, tvTop, tvLeft + tvW, tvTop + tvH),
                height * 0.06f, height * 0.06f, paint);

        // Stand
        float standW = tvW * 0.38f;
        float standY = tvTop + tvH;
        float standBase = standY + height * 0.06f;
        canvas.drawLine(cx, standY, cx, standBase, paint);
        canvas.drawLine(cx - standW / 2f, standBase, cx + standW / 2f, standBase, paint);

        // AirPlay / Cast triangle inside screen
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        Path tri = new Path();
        float triS = height * 0.09f;
        tri.moveTo(cx, cy - triS * 0.9f);
        tri.lineTo(cx + triS, cy + triS * 0.75f);
        tri.lineTo(cx - triS, cy + triS * 0.75f);
        tri.close();
        canvas.drawPath(tri, paint);

        // Wireless cast waves arc
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xCC67E8F9);
        paint.setStrokeWidth(Math.max(2f, height * 0.024f));
        canvas.drawArc(new RectF(cx - tvW * 0.36f, cy - tvH * 0.40f, cx + tvW * 0.36f, cy + tvH * 0.40f),
                -135, 90, false, paint);

        // Modern glowing badge label "YANSITMA"
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(height * 0.15f);
        paint.setLetterSpacing(0.08f);
        paint.setColor(0xFFF0FDF4);
        canvas.drawText("YANSITMA", cx, height * 0.85f, paint);
        return out;
    }

    private static Bitmap drawInput(LaunchItem item, int width) {
        int height = Math.max(1, Math.round(width * 9f / 16f));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        boolean hdmi = item.inputId != null
                && item.inputId.toLowerCase(java.util.Locale.US).contains("hdmi");
        if (!hdmi) return drawGenericInput(out, canvas, paint, width, height);

        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[] { 0xFF082F49, 0xFF0E7490, 0xFF164E63 },
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        // HDMI connector silhouette & text centered nicely
        float left = width * 0.14f;
        float right = width * 0.44f;
        float top = height * 0.26f;
        float bottom = height * 0.72f;
        float bevel = height * 0.09f;
        Path connector = new Path();
        connector.moveTo(left + bevel, top);
        connector.lineTo(right - bevel, top);
        connector.lineTo(right, top + bevel);
        connector.lineTo(right, bottom - bevel);
        connector.lineTo(right - bevel, bottom);
        connector.lineTo(left + bevel, bottom);
        connector.lineTo(left, bottom - bevel);
        connector.lineTo(left, top + bevel);
        connector.close();
        paint.setColor(0xF2FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, height * 0.035f));
        canvas.drawPath(connector, paint);

        float pinTop = top + height * 0.12f;
        float pinBottom = bottom - height * 0.12f;
        for (int i = 1; i <= 5; i++) {
            float x = left + (right - left) * i / 6f;
            canvas.drawLine(x, pinTop, x, pinBottom, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.WHITE);
        paint.setTextSize(height * 0.26f);
        canvas.drawText("HDMI", width * 0.50f, height * 0.48f, paint);

        String port = firstNumber(item.label);
        if (!port.isEmpty()) {
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setColor(0xCCFFFFFF);
            paint.setTextSize(height * 0.20f);
            canvas.drawText("PORT " + port, width * 0.50f, height * 0.72f, paint);
        }
        return out;
    }

    private static Bitmap drawGenericInput(Bitmap out, Canvas canvas, Paint paint,
                                           int width, int height) {
        paint.setColor(0xFF22D3EE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, height / 18f));
        RectF rect = new RectF(width * 0.24f, height * 0.18f, width * 0.76f, height * 0.72f);
        canvas.drawRoundRect(rect, height / 10f, height / 10f, paint);
        canvas.drawLine(width * 0.38f, height * 0.86f, width * 0.62f, height * 0.86f, paint);
        canvas.drawLine(width / 2f, height * 0.72f, width / 2f, height * 0.86f, paint);
        return out;
    }

    private static String firstNumber(String value) {
        if (value == null) return "";
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) return String.valueOf(value.charAt(i));
        }
        return "";
    }

    private static Bitmap fit(Bitmap source, int target) {
        if (source.getWidth() == target && source.getHeight() == target) return source;
        Bitmap out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min((float) target / source.getWidth(), (float) target / source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        canvas.drawBitmap(source, null,
                new android.graphics.RectF((target - width) / 2f, (target - height) / 2f,
                        (target + width) / 2f, (target + height) / 2f), paint);
        return out;
    }

    private JSONObject index() {
        try { return new JSONObject(indexPrefs.getString(INDEX_KEY, "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
