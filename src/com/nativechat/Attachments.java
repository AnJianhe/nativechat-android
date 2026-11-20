package com.nativechat;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 附件本地化与上传。对应 `services/attachments.ts` + `api/client.ts` 的 uploadFile：
 * 1. 选文件时就复制到应用私有目录（原件留着，服务端判定 file_id 失效时才能重传）；
 * 2. 发送前 POST /anthropic/v1/files 拿 file_id；
 * 3. 消息里以 image / document 块引用 file_id。
 */
public final class Attachments {
    /** 与上传接口一致的单文件上限 */
    public static final long MAX_SIZE = 64L * 1024 * 1024;
    /** user_data（纯文本、代码、表格、电子书）的原文件上限更小 */
    public static final long MAX_USER_DATA_SIZE = 32L * 1024 * 1024;
    public static final int MAX_COUNT = 50;

    private final Context context;
    private final File dir;

    public Attachments(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(this.context.getFilesDir(), "files");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /** 把 SAF 选中的文件复制进私有目录，返回未上传的附件记录。 */
    public Msg.FileAtt importUri(Uri uri) throws Rejected {
        String name = displayName(uri);
        if (!UploadExts.accepts(name)) {
            throw new Rejected("不支持的文件格式：" + name);
        }
        String mime = resolveMime(uri, name);
        boolean image = mime.startsWith("image/");
        Msg.FileAtt file = new Msg.FileAtt();
        file.id = Util.newId();
        file.name = name;
        file.mimeType = mime;
        file.isImage = image;
        long limit = "user_data".equals(file.purpose()) ? MAX_USER_DATA_SIZE : MAX_SIZE;
        file.path = file.id + "_" + Util.safeName(name);
        File target = new File(dir, file.path);
        long total = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("无法读取文件");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                total += count;
                if (total > limit) {
                    out.close();
                    target.delete();
                    throw new Rejected("单个文件不能超过 " + (limit / 1024 / 1024) + "MB");
                }
                out.write(buffer, 0, count);
            }
            out.flush();
        } catch (Rejected rejected) {
            throw rejected;
        } catch (Exception error) {
            target.delete();
            throw new Rejected("读取文件失败：" + error.getMessage());
        }
        file.size = total;
        return file;
    }

    /** 附件原件的存放目录（导入时直接往里写）。 */
    public File rawDir() {
        return dir;
    }

    public File local(Msg.FileAtt file) {        if (file == null || file.path == null) {
            return null;
        }
        File target = new File(dir, file.path);
        return target.exists() ? target : null;
    }

    /** 读取本地原件；直接导入的历史附件没有原件时返回 null。 */
    public byte[] read(Msg.FileAtt file) {
        File target = local(file);
        if (target == null) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(target)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) target.length());
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Exception error) {
            return null;
        }
    }

    public boolean exists(Msg.FileAtt file) {
        return local(file) != null;
    }

    /** 上传并按 file_id 返回；本地没有原件返回 null（该附件只展示、不进入请求）。 */
    public String upload(String origin, String apiKey, Msg.FileAtt file, Api.Call call)
        throws Api.ApiError {
        File target = local(file);
        if (target == null) {
            return null;
        }
        long since = Diag.now();
        File temp = null;
        String mime = file.mimeType;
        String name = file.name;
        try {
            if (file.isImage) {
                // 统一转成标准 JPEG：HEIC/WebP 改后缀、或元数据损坏的图片，
                // 服务端会直接判定「不是受支持的图片」。转码后既能通过校验，体积也小一个量级。
                temp = normalize(target, file.id);
                if (temp != null) {
                    mime = "image/jpeg";
                    target = temp;
                }
            }
            JSONObject response = Api.uploadFile(origin, apiKey, name, mime, target,
                file.purpose(), call);
            String id = response.optString("id", null);
            Diag.log("UPLOAD", "完成 " + name + " · " + Util.formatSize(target.length())
                + " · " + Diag.millis(since) + "ms");
            return id;
        } catch (Api.ApiError error) {
            Diag.log("UPLOAD", "失败 " + name + " · " + Util.formatSize(target.length())
                + " · " + error.kind + " " + error.status + " · " + Diag.millis(since) + "ms");
            throw error;
        } finally {
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /**
     * 把图片重编码成标准 JPEG，铺白底去掉透明通道。
     *
     * 分辨率优先：手机截图常见 1080×2400，一旦按 2048 缩边就会掉到 540×1200，
     * 文字全糊。所以只有最长边超过 3072 才降采样，压缩靠调质量而不是砍分辨率。
     */
    private File normalize(File source, String id) {
        Bitmap bitmap = null;
        Bitmap flat = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Diag.log("UPLOAD", "转码失败：解不出尺寸，按原件上传");
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > 3072 || bounds.outHeight / sample > 3072) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) {
                Diag.log("UPLOAD", "转码失败：解码返回空，按原件上传");
                return null;
            }
            flat = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(flat);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, 0f, 0f, null);

            File out = new File(context.getCacheDir(), "up_" + id + ".jpg");
            int quality = 92;
            writeJpeg(flat, out, quality);
            // 体积过大只降质量，不降分辨率
            while (out.length() > 4L * 1024 * 1024 && quality > 70) {
                quality -= 10;
                writeJpeg(flat, out, quality);
            }
            if (out.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }
            Diag.log("UPLOAD", "转码 " + source.length() / 1024 + "KB → " + out.length() / 1024
                + "KB · " + flat.getWidth() + "x" + flat.getHeight() + " · q" + quality);
            return out;
        } catch (OutOfMemoryError error) {
            Diag.log("UPLOAD", "转码失败：内存不足，按原件上传");
            return null;
        } catch (Throwable error) {
            Diag.log("UPLOAD", "转码失败：" + error + "，按原件上传");
            return null;
        } finally {
            if (bitmap != null && bitmap != flat) {
                bitmap.recycle();
            }
            if (flat != null) {
                flat.recycle();
            }
        }
    }

    private static void writeJpeg(Bitmap bitmap, File out, int quality) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到名字就退回路径
        }
        String path = uri.getLastPathSegment();
        return path == null ? "附件" : path;
    }

    private String resolveMime(Uri uri, String name) {
        String type = null;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
            type = null;
        }
        if (type != null && !type.isEmpty()) {
            return type.toLowerCase(Locale.US);
        }
        String ext = Util.ext(name);
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        if ("bmp".equals(ext)) {
            return "image/bmp";
        }
        if ("pdf".equals(ext)) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }
}
