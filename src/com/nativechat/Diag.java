package com.nativechat;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：记录关键路径耗时、流式节奏、主线程卡顿与异常，卡住时可以导出文本排查。
 *
 * 写入是同步的（单条几十字节），但都发生在事件边界上，不进入热路径循环。
 */
public final class Diag {
    /** 单个日志文件上限；超过就滚动成 chat.1.log，只保留一份旧档。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    /** 主线程心跳间隔与判定卡顿的阈值。 */
    private static final long BEAT_MS = 250;
    private static final long STALL_MS = 400;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static File file;
    private static Writer writer;
    private static long bytes;
    private static long lastBeat;
    private static volatile boolean started;

    private Diag() {
    }

    public static void start(Context context) {
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            try {
                File dir = new File(context.getFilesDir(), "diag");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                file = new File(dir, "chat.log");
                if (file.exists() && file.length() > MAX_BYTES) {
                    File old = new File(dir, "chat.1.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                bytes = file.exists() ? file.length() : 0;
                writer = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            } catch (Exception error) {
                writer = null;
            }
            started = true;
        }
        installCrashHandler();
        banner(context);
        startHeartbeat();
    }

    private static void banner(Context context) {
        log("APP", "启动 v" + version(context)
            + " · Android " + Build.VERSION.RELEASE + "(" + Build.VERSION.SDK_INT + ")"
            + " · " + Build.MANUFACTURER + " " + Build.MODEL
            + " · 堆上限 " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB");
    }

    private static String version(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "?";
        }
    }

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log("CRASH", thread.getName() + "：" + stack(error));
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** 主线程心跳：实际间隔远大于计划间隔时，说明主线程被占住，记下来。 */
    private static void startHeartbeat() {
        lastBeat = SystemClock.uptimeMillis();
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                long gap = now - lastBeat;
                lastBeat = now;
                if (gap > STALL_MS) {
                    log("STALL", "主线程卡住 " + gap + "ms · " + heap());
                }
                MAIN.postDelayed(this, BEAT_MS);
            }
        }, BEAT_MS);
    }

    public static String heap() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long total = runtime.totalMemory() / (1024 * 1024);
        return "堆 " + used + "/" + total + "MB";
    }

    public static void log(String tag, String message) {
        if (!started) {
            return;
        }
        String line = TIME.format(new Date()) + " [" + Thread.currentThread().getName() + "] "
            + tag + " " + message + "\n";
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.flush();
                bytes += line.length();
                if (bytes > MAX_BYTES) {
                    rotate();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void rotate() {
        try {
            writer.close();
            File dir = file.getParentFile();
            File old = new File(dir, "chat.1.log");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(old);
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            bytes = 0;
            writer.write(TIME.format(new Date()) + " [diag] 日志滚动\n");
            writer.flush();
        } catch (Exception error) {
            writer = null;
        }
    }

    /** 供设置页导出：把日志原样写进目标流。 */
    public static long copyTo(OutputStream out) throws Exception {
        if (file == null || !file.exists()) {
            return 0;
        }
        synchronized (LOCK) {
            if (writer != null) {
                writer.flush();
            }
        }
        long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    public static long size() {
        return file == null ? 0 : file.length();
    }

    public static void clear() {
        synchronized (LOCK) {
            try {
                if (writer != null) {
                    writer.close();
                }
                File dir = file == null ? null : file.getParentFile();
                writer = null;
                bytes = 0;
                if (dir != null) {
                    file = new File(dir, "chat.log");
                    writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
                    log("DIAG", "日志已清空");
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static long now() {
        return System.nanoTime();
    }

    /** 毫秒耗时。 */
    public static long millis(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1_000_000L;
    }

    public static String stack(Throwable error) {
        StringBuilder text = new StringBuilder(error.toString());
        for (StackTraceElement frame : error.getStackTrace()) {
            text.append("\n    at ").append(frame);
            if (text.length() > 4000) {
                break;
            }
        }
        return text.toString();
    }
}
