package com.nativechat;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 用量仅来自服务端；价格由用户自行填写，不代表官方报价。 */
public final class UsageDialog {
    private static final String PREFS = "usage_prices_v1";
    private static final String[] KEYS = {"input_tokens", "cache_creation_input_tokens",
        "cache_read_input_tokens", "output_tokens"};
    private static final String[] LABELS = {"未缓存输入", "缓存写入", "缓存命中（读取）", "输出"};
    private static final String[] PRICE_KEYS = {"input", "write", "read", "output"};

    private UsageDialog() {}

    /** messages 为当前分支按时间排列的消息；不回退到更早的有用量回复。 */
    public static void show(Activity activity, List<Msg> messages) {
        Msg last = null;
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg item = messages.get(i);
                if (item != null && !item.isUser()) { last = item; break; }
            }
        }
        showReply(activity, last);
    }

    /** 也可直接查看指定回复；设置始终按用户当前配置估算，并非历史账单。 */
    public static void showReply(Activity activity, Msg reply) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);
        Palette p = Palette.of(activity);
        JSONObject usage = reply == null ? null : reply.usage;
        Long[] counts = new Long[KEYS.length];
        for (int i = 0; i < counts.length; i++) counts[i] = count(usage, KEYS[i]);
        Long inputTotal = sum(counts, 3);
        Long allTotal = sum(counts, 4);
        long timestamp = reply == null ? 0 : reply.createdAt;
        int minute = minuteOfDay(timestamp);
        int start = parseTime(prefs.getString("peak_start", ""));
        int end = parseTime(prefs.getString("peak_end", ""));
        boolean knownPeriod = minute >= 0 && start >= 0 && end >= 0 && start != end;
        boolean peak = knownPeriod && (start < end ? minute >= start && minute < end
            : minute >= start || minute < end);
        boolean flash = prefs.getBoolean("flash_builtin", false);
        String manualPeriod = prefs.getLong("flash_period_reply_time", -1) == timestamp && timestamp > 0
            ? prefs.getString("flash_period", "") : "";
        if (flash) {
            boolean weekend = false;
            if (timestamp > 0) {
                java.util.Calendar beijing = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                beijing.setTimeInMillis(timestamp);
                int day = beijing.get(java.util.Calendar.DAY_OF_WEEK);
                weekend = day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY;
            }
            knownPeriod = manualPeriod.equals("peak") || manualPeriod.equals("valley") || weekend;
            peak = manualPeriod.equals("peak");
        }
        String currency = flash ? "CNY" : prefs.getString("currency", "").trim();
        BigDecimal[] costs = new BigDecimal[KEYS.length];
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = knownPeriod && !currency.isEmpty();
        for (int i = 0; i < counts.length; i++) {
            BigDecimal price = null;
            if (knownPeriod && flash && i != 1) {
                String[] rates = peak ? new String[]{"2", "", "0.04", "8"} : new String[]{"1", "", "0.02", "4"};
                price = decimal(rates[i]);
            } else if (knownPeriod && !flash) {
                price = decimal(prefs.getString((peak ? "peak_" : "valley_") + PRICE_KEYS[i], ""));
            }
            // 缓存写入没有公布单价：按 0 计，不因此把整笔费用标成未知。
            if (i == 1 && price == null) price = BigDecimal.ZERO;
            if (counts[i] == null || price == null || currency.isEmpty()) complete = false;
            else {
                costs[i] = price.multiply(BigDecimal.valueOf(counts[i])).divide(new BigDecimal("1000000"));
                total = total.add(costs[i]);
            }
        }

        Dialog dialog = dialog(activity);
        LinearLayout root = panel(activity, p);
        root.addView(text(activity, "Token 用量与价格", 20, p.text, true));
        TextView subtitle = text(activity, reply == null ? "没有回复记录" : "最近一次请求 · 按当前配置估算", 13, p.textDim, false);
        marginTop(activity, root, subtitle, 6);
        LinearLayout columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        marginTop(activity, root, columns, 18);
        LinearLayout left = column(activity);
        LinearLayout right = column(activity);
        columns.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        columns.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(text(activity, "TOKEN 用量", 12, p.textDim, true));
        right.addView(text(activity, "对应费用", 12, p.textDim, true));
        for (int i = 0; i < counts.length; i++) {
            cell(activity, left, LABELS[i], counts[i] == null ? "未知" : counts[i] + " tokens", p);
            cell(activity, right, LABELS[i], costs[i] == null ? "未知" : format(costs[i]) + " " + currency, p);
        }
        cell(activity, left, "输入合计", inputTotal == null ? "未知" : inputTotal + " tokens", p);
        cell(activity, right, "费用合计", complete ? format(total) + " " + currency : "未知", p);
        cell(activity, left, "Token 总计（含输出）", allTotal == null ? "未知" : allTotal + " tokens", p);
        String time = timestamp > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(new Date(timestamp)) : "未知";
        TextView note = text(activity, "计价时段：" + (knownPeriod ? (peak ? "峰时" : "谷时") : "未知")
            + "  ·  回复时间：" + time + "\n"
            + (flash ? "Flash 内置费率；北京时间周六、周日自动空闲；工作日法定节假日无法自动核验，时段默认未知，请手工选择该回复的峰谷。工作日（不含法定节假日）9–12、14–18 为高峰。缓存写入按 0 计。\n"
                : "按设备当前时区（" + TimeZone.getDefault().getID() + "）判断，实际计费时间或时区可能不同。\n")
            + "仅为价格估算，不是账单；不含其他费用。仅显示最后一次请求，续写或重试不累计。输入含历史、问题及提示词等；计量口径以服务端为准。", 12, p.textDim, false);
        marginTop(activity, root, note, 16);
        LinearLayout buttons = buttons(activity);
        TextView configure = button(activity, "配置峰谷费率", p, false);
        TextView close = button(activity, "关闭", p, true);
        buttons.addView(configure, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(activity, 44), 1);
        closeParams.leftMargin = dp(activity, 10);
        buttons.addView(close, closeParams);
        marginTop(activity, root, buttons, 18);
        configure.setOnClickListener(v -> { dialog.dismiss(); configure(activity, reply); });
        close.setOnClickListener(v -> dialog.dismiss());
        showPanel(activity, dialog, root);
    }

    private static void configure(Activity activity, Msg reply) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);
        Palette p = Palette.of(activity);
        Dialog dialog = dialog(activity);
        LinearLayout root = panel(activity, p);
        root.addView(text(activity, "自定义峰谷费率", 20, p.text, true));
        marginTop(activity, root, text(activity, "填写币种及每百万 tokens 单价；留空表示未知。峰时按设备时区，起始包含、结束不包含，可跨午夜，其余为谷时。配置对所有模型共用。", 13, p.textDim, false), 8);
        final boolean[] useFlash = {prefs.getBoolean("flash_builtin", false)};
        final String[] selectedPeriod = {reply != null && reply.createdAt > 0 && prefs.getLong("flash_period_reply_time", -1) == reply.createdAt ? prefs.getString("flash_period", "") : ""};
        TextView mode = button(activity, "", p, false);
        Runnable paintMode = () -> mode.setText("价格方案：" + (useFlash[0] ? "DeepSeek Flash 内置" : "自定义") + "（点按切换）");
        paintMode.run();
        mode.setOnClickListener(v -> { useFlash[0] = !useFlash[0]; paintMode.run(); });
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, dp(activity, 44)); modeLp.topMargin = dp(activity, 12); root.addView(mode, modeLp);
        TextView period = button(activity, "", p, false);
        Runnable paintPeriod = () -> period.setText("Flash 时段：" + (selectedPeriod[0].equals("peak") ? "高峰" : selectedPeriod[0].equals("valley") ? "空闲" : "未知") + "（点按选择）");
        paintPeriod.run();
        period.setOnClickListener(v -> { selectedPeriod[0] = selectedPeriod[0].equals("peak") ? "valley" : selectedPeriod[0].equals("valley") ? "" : "peak"; paintPeriod.run(); });
        LinearLayout.LayoutParams periodLp = new LinearLayout.LayoutParams(-1, dp(activity, 44)); periodLp.topMargin = dp(activity, 8); root.addView(period, periodLp);
        marginTop(activity, root, text(activity, "Flash：北京时间周六、周日自动空闲；工作日（不含中国法定节假日）9–12、14–18 为高峰。工作日节假日无法自动核验，请手工选择时段，否则未知。手选可覆盖自动值。缓存写入按 0 计。以下字段仅用于自定义方案。", 12, p.textDim, false), 10);
        String[] names = {"currency", "peak_start", "peak_end", "peak_input", "peak_write", "peak_read", "peak_output", "valley_input", "valley_write", "valley_read", "valley_output"};
        String[] titles = {"币种（如 CNY）", "峰时开始（HH:mm）", "峰时结束（HH:mm）", "峰时 未缓存输入", "峰时 缓存写入", "峰时 缓存读取", "峰时 输出", "谷时 未缓存输入", "谷时 缓存写入", "谷时 缓存读取", "谷时 输出"};
        EditText[] fields = new EditText[names.length];
        for (int i = 0; i < names.length; i++) {
            marginTop(activity, root, text(activity, titles[i], 13, p.textDim, false), 12);
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            if (i >= 3) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            field.setTextColor(p.text);
            field.setHintTextColor(p.textDim);
            field.setTextSize(15);
            field.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
            field.setBackground(shape(activity, p.bg, p.stroke, 10));
            field.setText(prefs.getString(names[i], ""));
            root.addView(field, new LinearLayout.LayoutParams(-1, dp(activity, 44)));
            fields[i] = field;
        }
        LinearLayout actions = buttons(activity);
        TextView cancel = button(activity, "取消", p, false);
        TextView save = button(activity, "保存", p, true);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 44), 1);
        lp.leftMargin = dp(activity, 10);
        actions.addView(save, lp);
        marginTop(activity, root, actions, 18);
        cancel.setOnClickListener(v -> { dialog.dismiss(); showReply(activity, reply); });
        save.setOnClickListener(v -> {
            String[] values = new String[fields.length];
            for (int i = 0; i < fields.length; i++) values[i] = fields[i].getText().toString().trim();
            for (int i = 1; i <= 2; i++) {
                if (!values[i].isEmpty() && parseTime(values[i]) < 0) {
                    fields[i].setError("请输入 00:00 至 23:59"); fields[i].requestFocus(); return;
                }
            }
            if (!values[1].isEmpty() && !values[2].isEmpty() && parseTime(values[1]) == parseTime(values[2])) {
                fields[2].setError("峰时开始和结束不能相同"); fields[2].requestFocus(); return;
            }
            for (int i = 3; i < fields.length; i++) {
                if (!values[i].isEmpty() && decimal(values[i]) == null) {
                    fields[i].setError("请输入非负数字（最多 12 位小数）"); fields[i].requestFocus(); return;
                }
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("flash_builtin", useFlash[0]);
            editor.putString("flash_period", selectedPeriod[0]);
            editor.putLong("flash_period_reply_time", reply == null ? -1 : reply.createdAt);
            for (int i = 0; i < names.length; i++) editor.putString(names[i], values[i]);
            editor.apply();
            dialog.dismiss();
            showReply(activity, reply);
        });
        showPanel(activity, dialog, root);
    }

    private static Long sum(Long[] values, int length) {
        long total = 0;
        try {
            for (int i = 0; i < length; i++) {
                if (values[i] == null) return null;
                total = Math.addExact(total, values[i]);
            }
            return total;
        } catch (ArithmeticException e) { return null; }
    }

    private static int dp(Activity a, int value) { return (int) (value * a.getResources().getDisplayMetrics().density + 0.5f); }
    private static GradientDrawable shape(Activity a, int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(a, radius));
        shape.setStroke(Math.max(1, dp(a, 1)), stroke); return shape;
    }
    private static Dialog dialog(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }
    private static LinearLayout panel(Activity a, Palette p) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20); root.setPadding(pad, pad, pad, pad);
        root.setBackground(shape(a, p.surface, p.stroke, 22)); return root;
    }
    private static void showPanel(Activity a, Dialog d, LinearLayout root) {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(root);
        d.setContentView(scroll);
        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        if (window != null) {
            int width = Math.min(dp(a, 480), a.getResources().getDisplayMetrics().widthPixels - dp(a, 32));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private static TextView text(Activity a, String value, int size, int color, boolean bold) {
        TextView view = new TextView(a); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private static void marginTop(Activity a, LinearLayout parent, android.view.View child, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(a, margin); parent.addView(child, lp);
    }
    private static LinearLayout column(Activity a) {
        LinearLayout view = new LinearLayout(a); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private static void cell(Activity a, LinearLayout column, String label, String value, Palette p) {
        LinearLayout cell = column(a); cell.setPadding(0, dp(a, 10), dp(a, 8), 0);
        cell.addView(text(a, label, 12, p.textDim, false));
        cell.addView(text(a, value, 14, p.text, true)); column.addView(cell);
    }
    private static LinearLayout buttons(Activity a) { LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private static TextView button(Activity a, String label, Palette p, boolean primary) {
        TextView view = text(a, label, 14, primary ? Color.WHITE : p.text, true);
        view.setGravity(Gravity.CENTER); view.setBackground(shape(a, primary ? p.accent : p.bg, p.stroke, 12));
        view.setClickable(true); view.setFocusable(true); return view;
    }
    private static int parseTime(String s) {
        if (s == null || !s.matches("[0-2][0-9]:[0-5][0-9]")) return -1;
        int hour = Integer.parseInt(s.substring(0, 2));
        return hour < 24 ? hour * 60 + Integer.parseInt(s.substring(3)) : -1;
    }
    private static int minuteOfDay(long millis) {
        if (millis <= 0) return -1;
        java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(millis);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }
    private static Long count(JSONObject usage, String key) {
        if (usage == null) return null;
        Object value = usage.opt(key);
        if (!(value instanceof Number)) return null;
        try { BigDecimal n = new BigDecimal(value.toString()); if (n.signum() < 0) return null; return n.longValueExact(); }
        catch (RuntimeException e) { return null; }
    }
    private static BigDecimal decimal(String value) {
        if (value == null || !value.matches("[0-9]{1,18}(\\.[0-9]{1,12})?")) return null;
        try { return new BigDecimal(value); } catch (NumberFormatException e) { return null; }
    }
    private static String format(BigDecimal value) {
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.01")) < 0) {
            return "小于 0.01";
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}




