package com.nativechat;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 主题配色。取值全部来自 chat-demo 的设计变量（src/app/theme.css）：
 * 深色用 body[data-ds-dark-theme] 下的 --dsw-alias-* 解析结果，
 * 浅色用默认 body 的同一批别名，不是自己调的。
 */
public final class Palette {
    public final boolean dark;
    /** --dsw-alias-bg-base */
    public final int bg;
    /** --dsw-alias-bg-layer-2 / layer-3 */
    public final int surface;
    /** --dsw-specific-input-major */
    public final int composer;
    /** --dsw-alias-label-primary */
    public final int text;
    /** --dsw-alias-label-tertiary */
    public final int textDim;
    /** --dsw-alias-label-caption */
    public final int caption;
    /** --dsw-alias-brand-primary */
    public final int accent;
    /** --dsw-specific-bubble */
    public final int bubbleUser;
    /** --dsw-alias-border-l2 */
    public final int stroke;
    /** --dsw-alias-border-l2-darkmode-thin */
    public final int strokeThin;
    /** --dsw-alias-state-error-primary */
    public final int error;
    /** --dsw-alias-markdown-code-block */
    public final int codeBlock;
    /** --dsw-alias-markdown-inline-code */
    public final int inlineCode;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            // neutral-bluish-950
            bg = Color.parseColor("#151517");
            // neutral-bluish-850
            surface = Color.parseColor("#2C2C2E");
            // neutral-bluish-850
            composer = Color.parseColor("#2C2C2E");
            // neutral-bluish-50
            text = Color.parseColor("#F9FAFB");
            // neutral-bluish-400
            textDim = Color.parseColor("#ADB2B8");
            // neutral-bluish-600
            caption = Color.parseColor("#81858C");
            // blue-450
            accent = Color.parseColor("#5686FE");
            // neutral-bluish-850
            bubbleUser = Color.parseColor("#2C2C2E");
            // rgba(255,255,255,0.12)
            stroke = 0x1FFFFFFF;
            // rgba(255,255,255,0.06)
            strokeThin = 0x0FFFFFFF;
            // red-400
            error = Color.parseColor("#F25A5A");
            // neutral-bluish-900
            codeBlock = Color.parseColor("#1B1B1C");
            // neutral-bluish-850
            inlineCode = Color.parseColor("#2C2C2E");
        } else {
            // 参照浅色界面的观感：淡蓝灰底、白卡片、弱边框、深灰字
            bg = Color.parseColor("#F1F4FB");
            surface = Color.parseColor("#FFFFFF");
            composer = Color.parseColor("#FFFFFF");
            text = Color.parseColor("#23262F");
            textDim = Color.parseColor("#6E7480");
            caption = Color.parseColor("#878D99");
            accent = Color.parseColor("#4C6EF5");
            bubbleUser = Color.parseColor("#E9EEFB");
            stroke = 0x11000000;
            strokeThin = 0x0A000000;
            // red-600
            error = Color.parseColor("#EC1313");
            // neutral-bluish-50
            codeBlock = Color.parseColor("#F9FAFB");
            // neutral-bluish-100
            inlineCode = Color.parseColor("#EBEEF2");
        }
    }

    public static Palette of(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        return new Palette(prefs.getInt(MainActivity.KEY_THEME, 0) == 0);
    }
}
