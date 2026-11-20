package com.nativechat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

/** 轻量 Markdown 渲染：代码块、标题、列表、粗体、斜体、行内代码、链接。 */
public final class Markdown {
    private Markdown() {
    }

    public static CharSequence render(String source, int textColor, int codeColor) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null) {
            return out;
        }
        String[] lines = source.split("\n", -1);
        boolean inCode = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                if (inCode) {
                    out.append("\n");
                }
                continue;
            }
            int start = out.length();
            if (inCode) {
                out.append(line).append("\n");
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeColor), start, Math.max(start, out.length() - 1),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(textColor), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (line.startsWith("# ")) {
                out.append(line.substring(2)).append("\n");
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.25f), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (line.startsWith("## ")) {
                out.append(line.substring(3)).append("\n");
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.12f), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            String body = line;
            if (body.startsWith("- ") || body.startsWith("* ")) {
                body = "• " + body.substring(2);
            }
            inline(out, body, textColor);
            out.append("\n");
        }
        return out;
    }

    /** 行内：**粗体**、*斜体*、`代码`、[文字](链接)。 */
    private static void inline(SpannableStringBuilder out, String line, int textColor) {
        int cursor = 0;
        while (cursor < line.length()) {
            int bold = line.indexOf("**", cursor);
            int code = line.indexOf('`', cursor);
            int link = line.indexOf('[', cursor);
            int next = minIndex(bold, code, link);
            if (next < 0) {
                out.append(line.substring(cursor));
                return;
            }
            out.append(line, cursor, next);
            if (next == bold) {
                int close = line.indexOf("**", next + 2);
                if (close < 0) {
                    out.append(line.substring(next));
                    return;
                }
                int start = out.length();
                out.append(decode(line.substring(next + 2, close)));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cursor = close + 2;
            } else if (next == code) {
                int close = line.indexOf('`', next + 1);
                if (close < 0) {
                    out.append(line.substring(next));
                    return;
                }
                int start = out.length();
                out.append(line, next + 1, close);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(textColor), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cursor = close + 1;
            } else {
                int textEnd = line.indexOf(']', next);
                int urlStart = textEnd < 0 ? -1 : line.indexOf('(', textEnd);
                int urlEnd = urlStart < 0 ? -1 : line.indexOf(')', urlStart);
                if (textEnd < 0 || urlStart != textEnd + 1 || urlEnd < 0) {
                    out.append(line.substring(next, next + 1));
                    cursor = next + 1;
                    continue;
                }
                String text = line.substring(next + 1, textEnd);
                String url = line.substring(urlStart + 1, urlEnd);
                int start = out.length();
                out.append(text);
                out.setSpan(new URLSpan(url), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cursor = urlEnd + 1;
            }
        }
    }

    private static int minIndex(int a, int b, int c) {
        int result = -1;
        for (int value : new int[] {a, b, c}) {
            if (value >= 0 && (result < 0 || value < result)) {
                result = value;
            }
        }
        return result;
    }

    private static String decode(String text) {
        return text;
    }
}
