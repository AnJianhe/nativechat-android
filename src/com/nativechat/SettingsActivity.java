package com.nativechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：账户（Key、地址、模型）、应用（字号、外观、快照）、数据（导入导出、清空）、关于。 */
public class SettingsActivity extends Activity {
    private static final int REQUEST_EXPORT = 5001;
    private static final int REQUEST_IMPORT = 5002;
    private static final int REQUEST_RAW = 5003;
    private static final int REQUEST_DIAG = 5004;

    private Settings settings;
    private Db db;
    private volatile boolean busy;

    @Override
    public void onBackPressed() {
        if (busy) {
            Toast.makeText(this, "数据操作进行中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private void setBusy(boolean value) {
        busy = value;
        for (int id : new int[]{R.id.back, R.id.row_export, R.id.row_import,
            R.id.row_clear}) {
            View view = findViewById(id);
            if (view != null) {
                view.setEnabled(!value);
            }
        }
    }

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        settings = new Settings(this);
        db = new Db(this);

        Palette palette = Palette.of(this);
        findViewById(android.R.id.content).setBackgroundColor(palette.surface);
        for (int id : new int[]{R.id.card_account, R.id.card_app, R.id.card_data,
            R.id.card_about}) {
            android.graphics.drawable.GradientDrawable card =
                new android.graphics.drawable.GradientDrawable();
            card.setColor(palette.dark ? palette.composer : 0xFFFFFFFF);
            card.setCornerRadius(getResources().getDisplayMetrics().density * 18);
            View view = findViewById(id);
            if (view != null) {
                view.setBackground(card);
            }
        }

        findViewById(R.id.back).setOnClickListener(view -> finish());
        findViewById(R.id.row_api_key).setOnClickListener(view -> editApiKey());
        findViewById(R.id.row_origin).setOnClickListener(view -> editOrigin());
        findViewById(R.id.row_verify).setOnClickListener(view -> verifyKey());
        findViewById(R.id.row_model).setOnClickListener(view -> chooseModel());
        findViewById(R.id.row_font).setOnClickListener(view -> chooseFont());
        findViewById(R.id.row_theme).setOnClickListener(view -> chooseTheme());
        findViewById(R.id.row_location).setOnClickListener(view -> editLocation());
        findViewById(R.id.row_language).setOnClickListener(view -> chooseLanguage());
        findViewById(R.id.row_history_thinking).setOnClickListener(view -> toggleHistoryThinking());
        findViewById(R.id.row_temperature).setOnClickListener(view -> editTemperature());
        findViewById(R.id.row_context_limit).setOnClickListener(view -> chooseContextLimit());
        findViewById(R.id.row_auto_compact).setOnClickListener(view -> toggleAutoCompact());
        findViewById(R.id.row_compact_style).setOnClickListener(view -> chooseCompactStyle());
        findViewById(R.id.row_compact_verify).setOnClickListener(view -> toggleCompactVerify());
        findViewById(R.id.row_export).setOnClickListener(view -> pickExport());
        findViewById(R.id.row_import).setOnClickListener(view -> pickImport());
        findViewById(R.id.row_clear).setOnClickListener(view -> confirmClear());
        findViewById(R.id.row_diag).setOnClickListener(view -> exportDiag());
        refresh();
    }

    private void refresh() {
        String key = settings.apiKey();
        String masked = key.isEmpty() ? "未设置"
            : key.length() <= 8 ? "****" : key.substring(0, 4) + "…" + key.substring(key.length() - 4);
        ((TextView) findViewById(R.id.api_key_value)).setText(masked);
        ((TextView) findViewById(R.id.origin_value)).setText(settings.origin());
        ((TextView) findViewById(R.id.model_value)).setText(settings.model());
        ((TextView) findViewById(R.id.font_value)).setText(fontLabel(settings.font()));
        ((TextView) findViewById(R.id.theme_value)).setText(settings.theme() == 0 ? "深色" : "浅色");
        String location = settings.reminderLocation();
        ((TextView) findViewById(R.id.location_value)).setText(
            location.isEmpty() ? "未设置" : location);
        ((TextView) findViewById(R.id.language_value)).setText(settings.reminderLanguage());
        ((TextView) findViewById(R.id.history_thinking_value)).setText(
            settings.sendHistoryThinking()
                ? "已开启：所有历史轮次的思考块都会发送（更费 token）"
                : "已关闭：只有带附件的轮次会发思考（图片的内容记在思考里）");
        ((TextView) findViewById(R.id.temperature_value)).setText(
            String.format(java.util.Locale.US, "%.2f", settings.temperature())
                + "　0 最稳定，1 最发散，0.8 为默认");
        ((TextView) findViewById(R.id.context_limit_value)).setText(
            formatTokens(settings.contextLimit()) + " tokens　用于计算「剩余上下文」");
        ((TextView) findViewById(R.id.auto_compact_value)).setText(
            settings.autoCompact()
                ? "已开启：接近上限时自动把较早的轮次压成摘要"
                : "已关闭：超出上限时请求会直接报错");
        ((TextView) findViewById(R.id.compact_style_value)).setText(styleLabel(settings.compactStyle()));
        ((TextView) findViewById(R.id.compact_verify_value)).setText(
            settings.compactVerify()
                ? "已开启：新摘要会向旧上下文追问，再据答案修订（多 3 次调用）"
                : "已关闭：直接生成摘要，省 token");
        long size = Diag.size();
        ((TextView) findViewById(R.id.diag_value)).setText(size == 0 ? "暂无日志"
            : Util.formatSize(size) + " · 卡住或报错时导出，发给开发者排查");
    }

    private String fontLabel(int level) {
        return level == 0 ? "小" : level == 2 ? "大" : "标准";
    }

    private AlertDialog.Builder field(String title, String value, boolean password,
                                      java.util.function.Consumer<String> save) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setSelection(input.getText().length());
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad, pad, 0);
        box.addView(input);
        return new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> {
                save.accept(input.getText().toString().trim());
                refresh();
            });
    }

    private void editApiKey() {
        field("API Key", settings.apiKey(), true, value ->
            settings.raw().edit().putString(Settings.KEY_API, value).apply()).show();
    }

    private void editOrigin() {
        field("接口地址", settings.origin(), false, value ->
            settings.raw().edit().putString(Settings.KEY_ORIGIN,
                value.isEmpty() ? Settings.DEFAULT_ORIGIN : value).apply()).show();
    }

    private void editLocation() {
        field("位置（写进本轮快照）", settings.reminderLocation(), false, value ->
            settings.raw().edit().putString(Settings.KEY_LOCATION, value).apply()).show();
    }

    /** 用 GET /models 校验 Key，不产生费用。 */
    private void verifyKey() {
        final String key = settings.apiKey();
        if (key.isEmpty()) {
            Toast.makeText(this, "先填 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在校验…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                Api.verifyKey(settings.origin(), key);
                message = "Key 有效";
            } catch (Api.ApiError error) {
                message = "校验失败：" + Describe.describeUpload(error)
                    + (error.getMessage().isEmpty() ? "" : "（" + error.getMessage() + "）");
            }
            final String text = message;
            runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "verify-key").start();
    }

    private void chooseModel() {
        String[] models = {"deepseek-flash", "deepseek-v4-pro"};
        int current = "deepseek-v4-pro".equals(settings.model()) ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle("模型")
            .setSingleChoiceItems(models, current, (dialog, which) -> {
                settings.raw().edit().putString(Settings.KEY_MODEL, models[which]).apply();
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private void chooseLanguage() {
        String[] options = {"中文", "English"};
        int current = "English".equals(settings.reminderLanguage()) ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle("快照语言")
            .setSingleChoiceItems(options, current, (dialog, which) -> {
                settings.raw().edit().putString(Settings.KEY_LANGUAGE, options[which]).apply();
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private void chooseContextLimit() {
        final int[] values = {32768, 65536, 128000, 256000, 1000000};
        String[] options = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            options[index] = formatTokens(values[index]);
        }
        new AlertDialog.Builder(this)
            .setTitle("上下文上限")
            .setItems(options, (dialog, which) -> {
                settings.setContextLimit(values[which]);
                refresh();
            })
            .show();
    }

    private void toggleAutoCompact() {
        String[] options = {"关闭", "开启（推荐）"};
        new AlertDialog.Builder(this)
            .setTitle("自动压缩上下文")
            .setSingleChoiceItems(options, settings.autoCompact() ? 1 : 0, (dialog, which) -> {
                settings.setAutoCompact(which == 1);
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private void chooseCompactStyle() {
        String[] options = {"自动判断（推荐）", "技术型", "陪伴型", "普通"};
        final int[] values = {Compactor.STYLE_AUTO, Compactor.STYLE_TECH,
            Compactor.STYLE_COMPANION, Compactor.STYLE_GENERAL};
        int current = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index] == settings.compactStyle()) {
                current = index;
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("压缩风格")
            .setSingleChoiceItems(options, current, (dialog, which) -> {
                settings.setCompactStyle(values[which]);
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private void toggleCompactVerify() {
        String[] options = {"关闭（省 token）", "开启（更准）"};
        new AlertDialog.Builder(this)
            .setTitle("压缩校验")
            .setSingleChoiceItems(options, settings.compactVerify() ? 1 : 0, (dialog, which) -> {
                settings.setCompactVerify(which == 1);
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private static String styleLabel(int style) {
        if (style == Compactor.STYLE_TECH) {
            return "技术型：保留代码、命令、参数、报错与结论";
        }
        if (style == Compactor.STYLE_COMPANION) {
            return "陪伴型：保留称呼、关系、情绪脉络与约定";
        }
        if (style == Compactor.STYLE_GENERAL) {
            return "普通：保留目标、结论与未完成事项";
        }
        return "自动判断：压缩前先让模型判断这段对话属于哪类";
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1000000) {
            return (tokens / 1000000) + "M";
        }
        return (tokens / 1000) + "K";
    }

    private void editTemperature() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (density * 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        final TextView label = new TextView(this);
        label.setTextSize(18f);
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(20);
        bar.setProgress(Math.round(settings.temperature() * 20f));
        label.setText(tempLabel(bar.getProgress()));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seek, int value, boolean fromUser) {
                label.setText(tempLabel(value));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seek) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seek) {
            }
        });

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText("0.00 最稳定，1.00 最发散。改完对下一条消息生效。");
        hint.setPadding(0, (int) (density * 6), 0, 0);

        box.addView(label);
        box.addView(bar);
        box.addView(hint);

        new AlertDialog.Builder(this)
            .setTitle("温度")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复 0.80", (dialog, which) -> {
                settings.setTemperature(Settings.DEFAULT_TEMPERATURE);
                refresh();
            })
            .setPositiveButton("保存", (dialog, which) -> {
                settings.setTemperature(bar.getProgress() / 20f);
                refresh();
            })
            .show();
    }

    private static String tempLabel(int progress) {
        float value = progress / 20f;
        String mood = value < 0.3f ? "稳妥" : value < 0.7f ? "均衡" : value < 0.9f ? "略发散" : "发散";
        return String.format(java.util.Locale.US, "%.2f　%s", value, mood);
    }

    private void toggleHistoryThinking() {
        String[] options = {"不发送（推荐，省 token）", "发送（模型能看到自己以前的思考）"};
        int current = settings.sendHistoryThinking() ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle("历史思考过程发给模型")
            .setSingleChoiceItems(options, current, (dialog, which) -> {
                settings.setSendHistoryThinking(which == 1);
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    private void chooseTheme() {
        String[] options = {"深色", "浅色"};
        new AlertDialog.Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(options, settings.theme(), (dialog, which) -> {
                settings.raw().edit().putInt(Settings.KEY_THEME, which).apply();
                dialog.dismiss();
                recreate();
            })
            .show();
    }

    private void chooseFont() {
        String[] levels = {"小", "标准", "大"};
        new AlertDialog.Builder(this)
            .setTitle("字体大小")
            .setSingleChoiceItems(levels, settings.font(), (dialog, which) -> {
                settings.raw().edit().putInt(Settings.KEY_FONT, which).apply();
                dialog.dismiss();
                refresh();
            })
            .show();
    }

    // ================================================================ 导入导出

    private void exportDiag() {
        Diag.start(this);
        Diag.log("DIAG", "用户导出日志");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
            "chat-diag-" + Util.dateYmd(System.currentTimeMillis()) + ".log");
        startActivityForResult(intent, REQUEST_DIAG);
    }

    private void writeDiag(final Uri uri) {
        new Thread(() -> {
            String message;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new java.io.IOException("无法写入目标文件");
                }
                long bytes = Diag.copyTo(out);
                out.flush();
                message = bytes == 0 ? "日志还是空的（先复现一次问题再导出）"
                    : "已导出 " + Util.formatSize(bytes) + " 日志";
            } catch (Exception error) {
                message = "导出失败：" + error.getMessage();
            }
            final String text = message;
            runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "diag-export").start();
    }

    private void pickExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,
            "deepseek_data-" + Util.dateYmd(System.currentTimeMillis()) + ".zip");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    /**
     * 一个入口导入所有格式，先识别再决定怎么导：
     * - 官方导出的 deepseek_data-*.zip（含 manifest.json）：整体替换，需二次确认；
     * - 网页版原始数据包 / NDJSON 迁移流：按 id 合并，不删已有会话。
     * 这样选错文件也不会只得到一句“格式不对”。
     */
    private void pickImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void detectAndImport(final Uri uri) {
        setBusy(true);
        Toast.makeText(this, "正在识别文件…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean history = Archive.isHistoryZip(this, uri);
            runOnUiThread(() -> {
                if (!history) {
                    startRawImport(uri);
                    return;
                }
                new AlertDialog.Builder(this)
                    .setMessage("识别为官方导出的历史数据包（v3）。\n\n"
                        + "导入会用文件里的会话替换本机全部会话与附件；替换在一个事务里完成，"
                        + "失败不会改动现有数据。继续？")
                    .setNegativeButton("取消", (dialog, which) -> setBusy(false))
                    .setPositiveButton("替换导入", (dialog, which) -> startArchiveImport(uri))
                    .show();
            });
        }, "detect").start();
    }

    private void startArchiveImport(final Uri uri) {
        Toast.makeText(this, "正在导入历史数据包…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                Archive.Result result = Archive.importArchive(this, db, uri);
                message = "已导入 " + result.sessions + " 个会话、"
                    + result.messages + " 条消息";
            } catch (Archive.Backup error) {
                message = error.getMessage();
            } catch (Exception error) {
                message = "导入失败：" + error.getMessage();
            }
            final String text = message;
            runOnUiThread(() -> {
                setBusy(false);
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            });
        }, "import").start();
    }

    private void startRawImport(final Uri uri) {
        Toast.makeText(this, "正在导入原始数据包…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new java.io.IOException("无法读取所选文件");
                }
                RawImport.Result result = RawImport.importStream(this, db, in);
                message = "已导入 " + result.sessions + " 个会话、"
                    + result.messages + " 条消息、"
                    + result.files + " 个附件（" + result.format + "）";
            } catch (RawImport.Invalid error) {
                message = error.getMessage();
            } catch (Exception error) {
                message = "导入失败：" + error.getMessage();
            }
            final String text = message;
            runOnUiThread(() -> {
                setBusy(false);
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            });
        }, "raw-import").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            setBusy(true);
            Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String message;
                try {
                    Archive.Result result = Archive.export(this, db, uri);
                    message = "已导出 " + result.sessions + " 个会话、"
                        + result.messages + " 条消息、" + result.files + " 张图片";
                } catch (Exception error) {
                    message = "导出失败：" + error.getMessage();
                }
                final String text = message;
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                });
            }, "export").start();
            return;
        }
        if (requestCode == REQUEST_IMPORT) {
            detectAndImport(uri);
        }
        if (requestCode == REQUEST_DIAG) {
            writeDiag(uri);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setMessage("清空全部会话？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (dialog, which) -> {
                for (Db.Session session : db.sessions()) {
                    db.deleteSession(session.id);
                }
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
}
