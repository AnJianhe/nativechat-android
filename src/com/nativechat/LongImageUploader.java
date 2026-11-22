package com.nativechat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Explicit, request-scoped long-image upload; never scans conversation history. */
public final class LongImageUploader {
    private static final int EDGE = 1024;
    private static final int MAX_TILES = 50;
    private LongImageUploader() { }

    private static final class ImagePlan {
        int rawWidth, rawHeight, orientation, height, sample, count;
        boolean longImage;
    }

    private static ImagePlan plan(File source, boolean image) throws Api.ApiError {
        ImagePlan plan = new ImagePlan();
        plan.count = 1;
        if (!image || source == null) return plan;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        plan.rawWidth = bounds.outWidth;
        plan.rawHeight = bounds.outHeight;
        plan.orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            plan.orientation = new ExifInterface(source.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) { }
        if (plan.orientation < 1 || plan.orientation > 8) plan.orientation = 1;
        boolean swap = plan.orientation >= 5 && plan.orientation <= 8;
        int w = swap ? plan.rawHeight : plan.rawWidth;
        int h = swap ? plan.rawWidth : plan.rawHeight;
        plan.height = h;
        plan.longImage = w > 0 && h > 3072 && (long) h >= 3L * w;
        if (!plan.longImage) return plan;
        plan.sample = 1;
        while (((long) w + plan.sample - 1) / plan.sample > EDGE && plan.sample < (1 << 30))
            plan.sample *= 2;
        long step = (long) EDGE * plan.sample;
        long count = ((long) h + step - 1) / step;
        if (count > MAX_TILES) throw new Api.ApiError(Api.UPLOAD,
            "长图切片过多，无法上传（最多 " + MAX_TILES + " 块）", -1);
        plan.count = (int) count;
        return plan;
    }

    /** Preflight before uploading anything: sum this count for all current attachments.
     * Missing originals count as one; the caller must separately validate availability.
     * The source must remain unchanged between preflight and upload.
     */
    public static int count(Attachments attachments, Msg.FileAtt file) throws Api.ApiError {
        return plan(attachments.local(file), file.isImage).count;
    }

    public static boolean isLongImage(Attachments attachments, Msg.FileAtt file) throws Api.ApiError {
        return plan(attachments.local(file), file.isImage).longImage;
    }

    /** Best-effort cleanup of request-scoped slice IDs, never ordinary cached IDs. */
    public static void cleanupAsync(String origin, String key, List<String> sliceIds) {
        if (sliceIds == null || sliceIds.isEmpty()) return;
        final List<String> copy = new ArrayList<>(sliceIds);
        Thread worker = new Thread(() -> {
            for (String id : copy) {
                if (id == null || id.trim().isEmpty()) continue;
                try { Api.deleteFile(origin, key, id); }
                catch (Api.ApiError | RuntimeException ignored) { }
            }
        }, "long-image-cleanup");
        worker.setDaemon(true);
        worker.start();
    }
    /** Returns null when the original is unavailable. Otherwise returns ordered file IDs.
     * Ordinary images and documents retain the original upload path. Call only for attachments
     * that the caller has already decided to include in this specific request. IDs are not persisted;
     * on retry invoke again, or retain the entire successful result for that request only.
     */
    public static List<String> upload(Attachments attachments, File cacheDir, String origin,
                                      String key, Msg.FileAtt file, Api.Call call)
            throws Api.ApiError {
        File source = attachments.local(file);
        if (source == null) return null;
        ImagePlan plan = plan(source, file.isImage);
        if (!plan.longImage) return single(attachments.upload(origin, key, file, call));
        int orientation = plan.orientation;
        int h = plan.height;
        int sample = plan.sample;
        int count = plan.count;
        long step = (long) EDGE * sample;
        BitmapRegionDecoder decoder = null;
        List<String> ids = new ArrayList<>();
        boolean complete = false;
        try {
            decoder = BitmapRegionDecoder.newInstance(source.getAbsolutePath(), false);
            if (decoder == null) throw new IOException("图片格式不支持区域解码");
            for (int i = 0; i < count; i++) {
                if (call != null && call.isCancelled())
                    throw new Api.ApiError(Api.INTERRUPTED, "Cancelled", -1);
                int top = (int) (i * step);
                int bottom = (int) Math.min((long) h, (i + 1L) * step);
                Rect rect = rawRect(plan.rawWidth, plan.rawHeight, orientation, top, bottom);
                Bitmap bitmap = null, transformed = null, flat = null;
                File temp = null;
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    bitmap = decoder.decodeRegion(rect, options);
                    if (bitmap == null) throw new IOException("切片解码失败");
                    Matrix matrix = matrix(orientation);
                    transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    flat = Bitmap.createBitmap(transformed.getWidth(), transformed.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(flat);
                    canvas.drawColor(Color.WHITE);
                    canvas.drawBitmap(transformed, 0, 0, null);
                    temp = File.createTempFile("long_image_", ".jpg", cacheDir);
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        if (!flat.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("切片编码失败");
                    }
                    JSONObject response = Api.uploadFile(origin, key, "part-" + (i + 1) + ".jpg",
                        "image/jpeg", temp, file.purpose(), call);
                    String id = response.optString("id", "");
                    if (id.trim().isEmpty()) throw new IOException("上传响应缺少文件 ID");
                    ids.add(id);
                } finally {
                    if (temp != null) temp.delete();
                    if (flat != null && flat != transformed && flat != bitmap) flat.recycle();
                    if (transformed != null && transformed != bitmap) transformed.recycle();
                    if (bitmap != null) bitmap.recycle();
                }
            }
            complete = true;
            return ids;
        } catch (Api.ApiError e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Api.ApiError(Api.UPLOAD, "长图切片失败：" + e.getMessage(), -1);
        } catch (OutOfMemoryError e) {
            throw new Api.ApiError(Api.UPLOAD, "长图切片失败：内存不足", -1);
        } finally {
            if (decoder != null) decoder.recycle();
            if (!complete) cleanupAsync(origin, key, ids);
        }
    }

    private static List<String> single(String id) {
        if (id == null) return null;
        List<String> ids = new ArrayList<>();
        ids.add(id);
        return ids;
    }

    // Map an upright horizontal band back to the encoded pixel coordinates.
    private static Rect rawRect(int w, int h, int o, int top, int bottom) {
        switch (o) {
            case 3: case 4: return new Rect(0, h - bottom, w, h - top);
            case 5: case 6: return new Rect(top, 0, bottom, h);
            case 7: case 8: return new Rect(w - bottom, 0, w - top, h);
            default: return new Rect(0, top, w, bottom);
        }
    }

    private static Matrix matrix(int o) {
        Matrix m = new Matrix();
        switch (o) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(90); m.postScale(1, -1); break;
            case 8: m.setRotate(270); break;
            default: break;
        }
        return m;
    }
}
