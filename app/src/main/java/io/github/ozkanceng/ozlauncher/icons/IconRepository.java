package io.github.ozkanceng.ozlauncher.icons;

import android.annotation.SuppressLint;

import android.content.Context;
import android.content.SharedPreferences;
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
import android.graphics.drawable.Drawable;
import android.net.Uri;
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
                    bitmap = drawBrandCard(item, sizePx);
                    if (bitmap == null) bitmap = loadApplication(item, sizePx);
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

    private static void applyCardClip(Canvas canvas, int width, int height) {
        float radius = height * 0.20f;
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
    }

    private Bitmap loadApplication(LaunchItem item, int sizePx) {
        try {
            Drawable drawable = null;
            boolean isBanner = false;
            if (item.resolveInfo != null && item.resolveInfo.activityInfo != null) {
                drawable = item.resolveInfo.activityInfo.loadBanner(context.getPackageManager());
                if (drawable == null && item.resolveInfo.activityInfo.applicationInfo != null) {
                    drawable = item.resolveInfo.activityInfo.applicationInfo.loadBanner(context.getPackageManager());
                }
                isBanner = drawable != null;
            }
            if (drawable == null && item.resolveInfo != null) {
                drawable = item.resolveInfo.loadIcon(context.getPackageManager());
            }
            if (drawable == null) return null;
            int heightPx = Math.max(1, Math.round(sizePx * 0.58f));
            Bitmap out = Bitmap.createBitmap(sizePx, heightPx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, sizePx, heightPx);

            // Sleek obsidian / slate glass TV card background
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setShader(new LinearGradient(0, 0, sizePx, heightPx,
                    new int[] { 0xFF242C3D, 0xFF141926, 0xFF0C0F17 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, sizePx, heightPx, cardPaint);

            if (isBanner) {
                // TV Banners fill the card smoothly with subtle top-to-bottom edge shading
                drawable.setBounds(0, 0, sizePx, heightPx);
                drawable.draw(canvas);
            } else {
                // Mobile app icons sit elegantly centered with an ambient glow behind them
                float cx = sizePx / 2f;
                float cy = heightPx / 2f;
                cardPaint.setShader(new RadialGradient(cx, cy, heightPx * 0.40f,
                        new int[] { 0x3338BDF8, 0x00000000 }, null, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, heightPx * 0.40f, cardPaint);

                int iconSize = Math.round(heightPx * 0.58f);
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

    private static Bitmap drawAction(LaunchItem item, int width) {
        int height = Math.max(1, Math.round(width * 0.58f));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        applyCardClip(canvas, width, height);

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

    private static Bitmap drawBrandCard(LaunchItem item, int width) {
        if (item == null) return null;
        String pkg = (item.packageName == null ? "" : item.packageName).toLowerCase(java.util.Locale.US);
        String label = (item.label == null ? "" : item.label).toLowerCase(java.util.Locale.US);
        String id = (item.id == null ? "" : item.id).toLowerCase(java.util.Locale.US);

        int height = Math.max(1, Math.round(width * 0.58f));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. Ayarlar / Settings
        if (pkg.contains("settings") || id.contains("settings") || label.contains("ayar") || label.contains("setting")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF2A3447, 0xFF171E2D, 0xFF0E131D }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            float cy = height * 0.44f;
            float r = height * 0.22f;
            paint.setShader(new RadialGradient(cx, cy, r * 1.8f,
                    new int[] { 0x4438BDF8, 0x1138BDF8, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 1.8f, paint);
            paint.setShader(null);

            paint.setColor(0xFFF1F5F9);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.28f);
            canvas.drawCircle(cx, cy, r * 0.72f, paint);

            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(i * 45);
                float cogX = cx + (float) Math.cos(angle) * r;
                float cogY = cy + (float) Math.sin(angle) * r;
                float cogW = r * 0.28f;
                float cogH = r * 0.22f;
                canvas.save();
                canvas.rotate(i * 45, cogX, cogY);
                canvas.drawRoundRect(new RectF(cogX - cogW/2f, cogY - cogH/2f, cogX + cogW/2f, cogY + cogH/2f),
                        r * 0.06f, r * 0.06f, paint);
                canvas.restore();
            }

            paint.setColor(0xFF38BDF8);
            canvas.drawCircle(cx, cy, r * 0.24f, paint);

            paint.setColor(0xFFF8FAFC);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            paint.setTextSize(height * 0.16f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.08f);
            canvas.drawText("AYARLAR", cx, height * 0.84f, paint);
            return out;
        }

        // 2. Canlı TV / TV Center
        if (pkg.contains("wwtv") || pkg.contains("tvcenter") || pkg.contains("livetv") || id.contains("tvcenter")
                || label.equals("tv") || label.contains("canlı tv") || label.contains("live tv")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF1E1B4B, 0xFF312E81, 0xFF0F172A }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            float cy = height * 0.42f;
            paint.setShader(new RadialGradient(cx, cy, height * 0.40f,
                    new int[] { 0x55A855F7, 0x15A855F7, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, height * 0.40f, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, height * 0.032f));
            paint.setColor(0xFFE0E7FF);
            float screenW = width * 0.38f;
            float screenH = height * 0.40f;
            float left = (width - screenW) / 2f;
            float top = height * 0.16f;
            canvas.drawRoundRect(new RectF(left, top, left + screenW, top + screenH),
                    height * 0.06f, height * 0.06f, paint);

            paint.setColor(0xFF38BDF8);
            paint.setStrokeWidth(Math.max(2f, height * 0.026f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawArc(new RectF(cx - screenW * 0.28f, cy - screenH * 0.28f, cx + screenW * 0.28f, cy + screenH * 0.28f),
                    -140, 100, false, paint);
            paint.setColor(0xFFC084FC);
            canvas.drawArc(new RectF(cx - screenW * 0.16f, cy - screenH * 0.16f, cx + screenW * 0.16f, cy + screenH * 0.16f),
                    -140, 100, false, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, height * 0.04f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xFFE0E7FF);
            paint.setStrokeWidth(Math.max(2f, height * 0.030f));
            float standY = top + screenH;
            float standBase = standY + height * 0.06f;
            canvas.drawLine(cx, standY, cx, standBase, paint);
            canvas.drawLine(cx - screenW * 0.25f, standBase, cx + screenW * 0.25f, standBase, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.16f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.06f);
            canvas.drawText("CANLI TV", cx, height * 0.86f, paint);
            return out;
        }

        // 3. Netflix
        if (pkg.contains("netflix") || label.contains("netflix") || id.contains("netflix")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF141414, 0xFF080808, 0xFF101010 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.55f,
                    new int[] { 0x55E50914, 0x15E50914, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.55f, paint);
            paint.setShader(null);

            paint.setColor(0xFFE50914);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.35f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.06f);
            canvas.drawText("NETFLIX", cx, height * 0.62f, paint);
            return out;
        }

        // 4. Max / HBO Max
        if (pkg.contains("wbd.stream") || pkg.contains("hbo") || label.contains("hbo")
                || label.equals("max") || label.contains("max") || id.contains("max")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF002878, 0xFF001048, 0xFF030514 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x4400A8E1, 0x1000A8E1, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.44f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(-0.02f);
            canvas.drawText("max", cx, height * 0.65f, paint);
            return out;
        }

        // 5. YouTube
        if (!pkg.contains("tvkids") && (pkg.contains("youtube") || label.contains("youtube") || id.contains("youtube"))) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF1F1F1F, 0xFF121212, 0xFF0A0A0A }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x40FF0000, 0x10FF0000, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setTextSize(height * 0.26f);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            float textW = paint.measureText("YouTube");

            float badgeH = height * 0.36f;
            float badgeW = badgeH * 1.42f;
            float gap = width * 0.04f;
            float totalW = badgeW + gap + textW;
            float startX = (width - totalW) / 2f;
            float by = (height - badgeH) / 2f;

            paint.setColor(0xFFFF0000);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(startX, by, startX + badgeW, by + badgeH),
                    badgeH * 0.28f, badgeH * 0.28f, paint);

            paint.setColor(Color.WHITE);
            Path tri = new Path();
            float tSize = badgeH * 0.32f;
            float tcx = startX + badgeW * 0.53f;
            float tcy = by + badgeH * 0.50f;
            tri.moveTo(tcx + tSize, tcy);
            tri.lineTo(tcx - tSize * 0.6f, tcy - tSize * 0.7f);
            tri.lineTo(tcx - tSize * 0.6f, tcy + tSize * 0.7f);
            tri.close();
            canvas.drawPath(tri, paint);

            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("YouTube", startX + badgeW + gap, by + badgeH * 0.73f, paint);
            return out;
        }

        // 6. YouTube Kids
        if (pkg.contains("tvkids") || label.contains("kids")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF0284C7, 0xFF0369A1, 0xFF0C4A6E }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            float cy = height * 0.44f;
            float badgeH = height * 0.38f;
            float badgeW = badgeH * 1.35f;

            paint.setColor(0xFFFF0000);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(cx - badgeW/2f, cy - badgeH/2f, cx + badgeW/2f, cy + badgeH/2f),
                    badgeH * 0.32f, badgeH * 0.32f, paint);

            paint.setColor(Color.WHITE);
            Path tri = new Path();
            float tSize = badgeH * 0.30f;
            tri.moveTo(cx + tSize * 0.9f, cy);
            tri.lineTo(cx - tSize * 0.6f, cy - tSize * 0.7f);
            tri.lineTo(cx - tSize * 0.6f, cy + tSize * 0.7f);
            tri.close();
            canvas.drawPath(tri, paint);

            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.18f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.04f);
            canvas.drawText("Kids", cx, height * 0.84f, paint);
            return out;
        }

        // 7. Spotify
        if (pkg.contains("spotify") || label.contains("spotify") || id.contains("spotify")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF1C1C1C, 0xFF121212, 0xFF080808 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x441DB954, 0x101DB954, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setTextSize(height * 0.25f);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            float textW = paint.measureText("Spotify");

            float r = height * 0.22f;
            float gap = width * 0.04f;
            float totalW = r * 2f + gap + textW;
            float startX = (width - totalW) / 2f;
            float circleX = startX + r;

            paint.setColor(0xFF1DB954);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(circleX, height * 0.50f, r, paint);

            paint.setColor(0xFF121212);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(r * 0.18f);
            canvas.drawArc(new RectF(circleX - r * 0.65f, height * 0.50f - r * 0.55f, circleX + r * 0.65f, height * 0.50f + r * 0.45f), -145, 110, false, paint);
            paint.setStrokeWidth(r * 0.16f);
            canvas.drawArc(new RectF(circleX - r * 0.50f, height * 0.50f - r * 0.30f, circleX + r * 0.50f, height * 0.50f + r * 0.55f), -145, 110, false, paint);
            paint.setStrokeWidth(r * 0.14f);
            canvas.drawArc(new RectF(circleX - r * 0.35f, height * 0.50f - r * 0.10f, circleX + r * 0.35f, height * 0.50f + r * 0.65f), -145, 110, false, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Spotify", circleX + r + gap, height * 0.58f, paint);
            return out;
        }

        // 8. tabii
        if (pkg.contains("tabii") || label.contains("tabii") || id.contains("tabii")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF064E3B, 0xFF042F2E, 0xFF021B1A }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x4410B981, 0x1010B981, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setColor(0xFF34D399);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.42f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.04f);
            canvas.drawText("tabii", cx, height * 0.64f, paint);
            return out;
        }

        // 9. TOD
        if (pkg.contains("tod") || label.contains("tod") || id.contains("tod")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF2E1065, 0xFF1E1B4B, 0xFF0F172A }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x55C084FC, 0x15C084FC, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setColor(0xFFE879F9);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.42f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.06f);
            canvas.drawText("TOD", cx, height * 0.64f, paint);
            return out;
        }

        // 10. Exxen
        if (pkg.contains("exxen") || label.contains("exxen") || id.contains("exxen")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF1C1917, 0xFF0C0A09, 0xFF000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setShader(new RadialGradient(cx, height * 0.50f, height * 0.50f,
                    new int[] { 0x44FACC15, 0x10FACC15, 0x00000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, height * 0.50f, height * 0.50f, paint);
            paint.setShader(null);

            paint.setColor(0xFFFACC15);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.38f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.08f);
            canvas.drawText("EXXEN", cx, height * 0.64f, paint);
            return out;
        }

        // 11. Prime Video
        if (pkg.contains("amazonvideo") || label.contains("prime") || pkg.contains("primevideo")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF0A192F, 0xFF020C1B, 0xFF01060D }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(height * 0.28f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setLetterSpacing(0.02f);
            canvas.drawText("prime video", cx, height * 0.56f, paint);

            paint.setColor(0xFF00A8E1);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, height * 0.038f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(new RectF(cx - width * 0.20f, height * 0.58f, cx + width * 0.20f, height * 0.78f),
                    25, 130, false, paint);
            return out;
        }

        // 12. Downloader
        if (pkg.contains("downloader") || label.contains("downloader")) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFFC2410C, 0xFF9A3412, 0xFF7C2D12 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setTextSize(height * 0.22f);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            float textW = paint.measureText("Downloader");
            float badgeW = height * 0.38f;
            float gap = width * 0.04f;
            float totalW = badgeW + gap + textW;
            float startX = (width - totalW) / 2f;
            float by = (height - badgeW) / 2f;

            paint.setColor(0x33FFFFFF);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(startX, by, startX + badgeW, by + badgeW),
                    badgeW * 0.28f, badgeW * 0.28f, paint);

            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            paint.setTextSize(badgeW * 0.72f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("D", startX + badgeW / 2f, by + badgeW * 0.76f, paint);

            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            paint.setTextSize(height * 0.22f);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Downloader", startX + badgeW + gap, height * 0.58f, paint);
            return out;
        }

        // 13. Apple TV
        if (pkg.contains("apple") && (pkg.contains("tv") || label.contains("tv"))) {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            applyCardClip(canvas, width, height);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] { 0xFF1C1917, 0xFF0C0A09, 0xFF000000 }, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            float cx = width / 2f;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            paint.setTextSize(height * 0.42f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("tv", cx, height * 0.65f, paint);
            return out;
        }

        return null;
    }

    private static Bitmap drawInput(LaunchItem item, int width) {
        int height = Math.max(1, Math.round(width * 0.58f));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        float radius = height * 0.20f;
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

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
