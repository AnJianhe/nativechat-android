package com.nativechat;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** 无依赖的小工具：ID、扩展名、ISO 时间、链接判断、体积格式化。 */
public final class Util {
    private Util() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** 小写扩展名（不含点）；没有扩展名时返回空串。 */
    public static String ext(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1
            ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** 文件名去掉目录与危险字符，用于落盘。 */
    public static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "file";
        }
        String base = new File(name).getName();
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < base.length(); index++) {
            char ch = base.charAt(index);
            boolean ok = Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_'
                || ch > 0x7F;
            out.append(ok ? ch : '_');
        }
        String text = out.toString();
        if (text.isEmpty() || text.equals(".") || text.equals("..")) {
            return "file";
        }
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    /** UTC RFC 3339，毫秒精度，Z 结尾；与导出格式一致。 */
    public static String iso(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.setTimeInMillis(millis);
        StringBuilder text = new StringBuilder(24);
        pad(text, calendar.get(Calendar.YEAR), 4).append('-');
        pad(text, calendar.get(Calendar.MONTH) + 1, 2).append('-');
        pad(text, calendar.get(Calendar.DAY_OF_MONTH), 2).append('T');
        pad(text, calendar.get(Calendar.HOUR_OF_DAY), 2).append(':');
        pad(text, calendar.get(Calendar.MINUTE), 2).append(':');
        pad(text, calendar.get(Calendar.SECOND), 2).append('.');
        pad(text, calendar.get(Calendar.MILLISECOND), 3).append('Z');
        return text.toString();
    }

    private static StringBuilder pad(StringBuilder out, int value, int width) {
        String text = String.valueOf(value);
        for (int index = text.length(); index < width; index++) {
            out.append('0');
        }
        return out.append(text);
    }

    /**
     * 严格解析 `YYYY-MM-DDTHH:MM:SS(.fraction)?Z`，校验范围后返回毫秒。
     * 小数秒按前三位截断（多余精度对本地存储无意义）。非法输入抛 IllegalArgumentException。
     */
    public static long parseIso(String value) {
        if (value == null || !value.endsWith("Z") || value.length() < 20) {
            throw new IllegalArgumentException("time");
        }
        String body = value.substring(0, value.length() - 1);
        if (body.length() < 19 || body.charAt(4) != '-' || body.charAt(7) != '-'
            || body.charAt(10) != 'T' || body.charAt(13) != ':' || body.charAt(16) != ':') {
            throw new IllegalArgumentException("time");
        }
        int year = number(body, 0, 4);
        int month = number(body, 5, 7);
        int day = number(body, 8, 10);
        int hour = number(body, 11, 13);
        int minute = number(body, 14, 16);
        int second = number(body, 17, 19);
        int millis = 0;
        if (body.length() > 19) {
            if (body.charAt(19) != '.') {
                throw new IllegalArgumentException("time");
            }
            String fraction = body.substring(20);
            if (fraction.isEmpty() || fraction.length() > 9) {
                throw new IllegalArgumentException("time");
            }
            StringBuilder digits = new StringBuilder(fraction);
            while (digits.length() < 3) {
                digits.append('0');
            }
            millis = number(digits.substring(0, 3), 0, 3);
        }
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59
            || second > 59) {
            throw new IllegalArgumentException("time");
        }
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        calendar.set(Calendar.MILLISECOND, millis);
        long result = calendar.getTimeInMillis();
        // 日期越界（如 2 月 30 日）会被 Calendar 归一化，回读校验拦住它
        Calendar check = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        check.setTimeInMillis(result);
        if (check.get(Calendar.YEAR) != year || check.get(Calendar.MONTH) != month - 1
            || check.get(Calendar.DAY_OF_MONTH) != day) {
            throw new IllegalArgumentException("time");
        }
        return result;
    }

    private static int number(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            char ch = text.charAt(index);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("time");
            }
        }
        return Integer.parseInt(text.substring(start, end));
    }

    /** 只接受 http / https；用于把工具块里的链接放进可点击的界面。 */
    public static boolean isWebUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return value < 10 ? String.format(Locale.US, "%.1f %s", value, units[unit])
            : String.format(Locale.US, "%.0f %s", value, units[unit]);
    }

    /** 中文日期与星期，供 latestReminder 使用（浏览器本地时区）。 */
    public static String dateYmd(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        StringBuilder text = new StringBuilder();
        pad(text, calendar.get(Calendar.YEAR), 4).append('-');
        pad(text, calendar.get(Calendar.MONTH) + 1, 2).append('-');
        pad(text, calendar.get(Calendar.DAY_OF_MONTH), 2);
        return text.toString();
    }

    public static String weekdayZh(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        String[] names = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
        return names[calendar.get(Calendar.DAY_OF_WEEK) - 1];
    }
}
