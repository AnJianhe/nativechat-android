package com.nativechat;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置项读写：Key、地址、模型、默认开关、字号、主题、快照用的位置与语言。 */
public final class Settings {
    public static final String PREFS = "nativechat";
    public static final String KEY_API = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_ORIGIN = "api_origin";
    public static final String KEY_FONT = "font";
    public static final String KEY_THEME = "theme";
    public static final String KEY_THINKING = "thinking";
    public static final String KEY_SEARCH = "search";
    public static final String KEY_LOCATION = "reminder_location";
    public static final String KEY_LANGUAGE = "reminder_language";
    /** 是否把历史轮次的思考过程（含签名）一起发给模型，默认关闭。 */
    public static final String KEY_SEND_THINKING = "send_history_thinking";
    /** 采样温度：默认 0.8，0 最稳定、1 最发散。 */
    public static final String KEY_TEMPERATURE = "temperature";
    public static final float DEFAULT_TEMPERATURE = 0.8f;
    /** 上下文窗口上限（token），用于显示剩余量。按用户模型实际规格取 1M。 */
    public static final String KEY_CONTEXT_LIMIT = "context_limit";
    public static final int DEFAULT_CONTEXT_LIMIT = 32768;
    /** 上下文接近上限时自动折叠较早轮次。 */
    public static final String KEY_AUTO_COMPACT = "auto_compact";
    /** 压缩风格：0 通用 / 1 技术 / 2 陪伴 / -1 自动判断。 */
    public static final String KEY_COMPACT_STYLE = "compact_style";
    /** 压缩校验：让新摘要与旧上下文互相提问修订，更准但更费 token。 */
    public static final String KEY_COMPACT_VERIFY = "compact_verify";
    public static final String KEY_ADAPTIVE_CONTEXT = "adaptive_context_experimental";

    public boolean adaptiveContext() {
        return prefs.getBoolean(KEY_ADAPTIVE_CONTEXT, false);
    }

    public void setAdaptiveContext(boolean enabled) {
        prefs.edit().putBoolean(KEY_ADAPTIVE_CONTEXT, enabled).apply();
    }

    public static final String DEFAULT_ORIGIN = "https://api.deepseek.com";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public String apiKey() {
        String key = Profiles.active(this).apiKey;
        return key == null ? "" : key.trim();
    }

    public String origin() {
        String value = Profiles.active(this).origin;
        if (value == null || value.trim().isEmpty()) {
            value = DEFAULT_ORIGIN;
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String model() {
        String value = Profiles.active(this).model;
        return value == null || value.isEmpty() || "deepseek-chat".equals(value)
            || "deepseek-reasoner".equals(value) ? Prompt.DEFAULT_MODEL : value;
    }

    public boolean thinking() {
        return prefs.getBoolean(KEY_THINKING, false);
    }

    public void setThinking(boolean value) {
        prefs.edit().putBoolean(KEY_THINKING, value).apply();
    }

    public boolean search() {
        return prefs.getBoolean(KEY_SEARCH, false);
    }

    public void setSearch(boolean value) {
        prefs.edit().putBoolean(KEY_SEARCH, value).apply();
    }

    /** 历史思考默认不进上下文：省 token、也避免长会话越滚越大。 */
    public boolean sendHistoryThinking() {
        return prefs.getBoolean(KEY_SEND_THINKING, false);
    }

    public void setSendHistoryThinking(boolean value) {
        prefs.edit().putBoolean(KEY_SEND_THINKING, value).apply();
    }

    public float temperature() {
        float value = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE);
        if (value < 0f) {
            return 0f;
        }
        return value > 1f ? 1f : value;
    }

    public void setTemperature(float value) {
        prefs.edit().putFloat(KEY_TEMPERATURE, Math.max(0f, Math.min(1f, value))).apply();
    }

    public int contextLimit() {
        int value = prefs.getInt(KEY_CONTEXT_LIMIT, DEFAULT_CONTEXT_LIMIT);
        return value < 8000 ? DEFAULT_CONTEXT_LIMIT : value;
    }

    public void setContextLimit(int value) {
        prefs.edit().putInt(KEY_CONTEXT_LIMIT, Math.max(8000, value)).apply();
    }

    public boolean autoCompact() {
        return prefs.getBoolean(KEY_AUTO_COMPACT, true);
    }

    public void setAutoCompact(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_COMPACT, value).apply();
    }

    public int compactStyle() {
        return prefs.getInt(KEY_COMPACT_STYLE, Compactor.STYLE_AUTO);
    }

    public void setCompactStyle(int value) {
        prefs.edit().putInt(KEY_COMPACT_STYLE, value).apply();
    }

    public boolean compactVerify() {
        return prefs.getBoolean(KEY_COMPACT_VERIFY, false);
    }

    public void setCompactVerify(boolean value) {
        prefs.edit().putBoolean(KEY_COMPACT_VERIFY, value).apply();
    }

    public int font() {
        return prefs.getInt(KEY_FONT, 1);
    }

    public int theme() {
        return prefs.getInt(KEY_THEME, 0);
    }

    /** 快照里的位置；没有设置时为空串（与网页版未授权定位时一致）。 */
    public String reminderLocation() {
        String value = prefs.getString(KEY_LOCATION, "");
        return value == null ? "" : value.trim();
    }

    public String reminderLanguage() {
        String value = prefs.getString(KEY_LANGUAGE, Prompt.DEFAULT_LANGUAGE);
        return value == null || value.isEmpty() ? Prompt.DEFAULT_LANGUAGE : value;
    }
}
