package com.nativechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 会话页。只做展示与输入：协议、上下文、附件、状态机都在 {@link Engine} 里，
 * 界面拿到的只是快照，不参与业务判断。
 */
public class ChatActivity extends Activity implements Engine.Listener {
    private static final int REQUEST_FILES = 2001;
    private static final int REQUEST_IMAGES = 2002;
    private static final int REQUEST_CAMERA = 2003;
    private static final int REQUEST_RECENT_IMAGES = 2004;
    private android.app.Dialog attachmentSheet;
    private android.net.Uri pendingCapture;
    private static final int REQUEST_AUDIO = 3001;

    private final List<Msg> messages = new ArrayList<>();
    private final List<Msg.FileAtt> attachments = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final java.util.Map<String, List<Msg>> siblingCache = new java.util.HashMap<>();
    /** 每次只渲染最近 PAGE 条，更早的按需载入。 */
    private static final int PAGE = 40;
    private int hidden;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Bounded work and cache prevent rapid scrolling from accumulating image decodes.
    private final java.util.concurrent.ThreadPoolExecutor thumbnails =
        new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(24),
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.util.LruCache<String, Bitmap> thumbnailCache =
        new android.util.LruCache<String, Bitmap>((int) Math.min(4 * 1024 * 1024L,
            Runtime.getRuntime().maxMemory() / 32)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

    private Db db;
    private Engine engine;
    private Settings settings;
    private Palette palette;
    private MessageAdapter adapter;
    private EditText input;
    private ImageView sendButton;
    private TextView attachmentsLabel;
    private TextView titleView;
    private TextView thinkingButton;
    private TextView searchButton;
    private View welcome;
    private TextView contextBar;
    private View drawer;
    private View scrim;
    private ListView list;
    private ListView drawerList;
    private EditText drawerSearch;
    private DrawerAdapter drawerAdapter;
    private String sessionId;
    private String title = "对话";
    private boolean thinking;
    private boolean search;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private String voiceBase = "";
    private boolean waitingForPreviousStream;
    private final Runnable streamWatcher = new Runnable() {
        @Override public void run() {
            if (engine.isStreaming()) {
                waitingForPreviousStream = true;
                main.postDelayed(this, 100);
            } else if (waitingForPreviousStream) {
                waitingForPreviousStream = false;
                if (sessionId != null) {
                    engine.settleInterrupted(sessionId);
                    loadMessages();
                }
                updateSendButton();
                refreshDrawer();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getString("camera_uri") != null) {
            pendingCapture = android.net.Uri.parse(state.getString("camera_uri"));
        }
        palette = Palette.of(this);
        settings = new Settings(this);
        Diag.start(this);
        setContentView(R.layout.activity_chat);
        Db.backupBeforeUpgrade(this);
        db = new Db(this);
        engine = new Engine(this, db, this);
        thinking = settings.thinking();
        search = settings.search();
        input = findViewById(R.id.input);
        list = findViewById(R.id.messages);
        sendButton = findViewById(R.id.send);
        attachmentsLabel = findViewById(R.id.attachments);
        titleView = findViewById(R.id.title);
        thinkingButton = findViewById(R.id.thinking);
        searchButton = findViewById(R.id.search);
        welcome = findViewById(R.id.welcome);
        contextBar = findViewById(R.id.context_bar);
        contextBar.setOnClickListener(view -> showContextDetail());
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.scrim);
        drawerList = findViewById(R.id.drawer_list);
        drawerSearch = findViewById(R.id.drawer_search);
        drawerSearch.setTextColor(palette.text);
        drawerSearch.setHintTextColor(palette.textDim);
        drawerSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                onSearchChanged(text.toString());
            }
        });
        applyTheme();

        drawerAdapter = new DrawerAdapter();
        drawerList.setAdapter(drawerAdapter);
        drawerList.setOnItemClickListener((parent, view, position, id) -> {
            if (!drawerAdapter.query.isEmpty()) {
                if (position < drawerAdapter.hits.size()) {
                    switchTo(drawerAdapter.hits.get(position).sessionId);
                }
            } else if (position < drawerAdapter.sessions.size()) {
                switchTo(drawerAdapter.sessions.get(position).id);
            }
            hideDrawer();
        });
        drawerList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (drawerAdapter.query.isEmpty() && position < drawerAdapter.sessions.size()) {
                sessionActions(drawerAdapter.sessions.get(position));
            }
            return true;
        });
        findViewById(R.id.drawer_new).setOnClickListener(view -> {
            startNewChat();
            hideDrawer();
        });
        findViewById(R.id.drawer_settings).setOnClickListener(view -> {
            hideDrawer();
            openSettings();
        });
        scrim.setOnClickListener(view -> hideDrawer());
        ((ImageView) findViewById(R.id.menu)).setOnClickListener(view -> showDrawer());
        titleView.setOnClickListener(view -> openSettings());
        findViewById(R.id.new_chat).setOnClickListener(view -> startNewChat());
        findViewById(R.id.attach).setOnClickListener(view -> chooseAttachment());
        findViewById(R.id.mic).setOnClickListener(view -> toggleVoice());
        thinkingButton.setOnClickListener(view -> {
            thinking = !thinking;
            settings.setThinking(thinking);
            paintToggles();
        });
        searchButton.setOnClickListener(view -> {
            search = !search;
            settings.setSearch(search);
            paintToggles();
        });
        sendButton.setOnClickListener(view -> {
            if (engine.isStreaming()) {
                if (waitingForPreviousStream) {
                    Toast.makeText(this, "正在停止上一轮，请稍候", Toast.LENGTH_SHORT).show();
                } else {
                    engine.stop();
                }
            } else {
                send();
            }
        });
        attachmentsLabel.setOnClickListener(view -> {
            attachments.clear();
            refreshAttachments();
        });

        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        // 滚到顶就自动往前补一页：需要多少渲染多少，不用一次性铺满整个会话。
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int state) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                if (first == 0 && total > 0 && hidden > 0) {
                    loadEarlier();
                }
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            int index = indexAt(position);
            if (index >= 0) {
                showActions(messages.get(index));
            }
            return true;
        });

        tts = new TextToSpeech(this, status -> {
            // 初始化失败时朗读会给出提示
        });

        String requested = state != null ? state.getString("session")
            : getIntent().getStringExtra("session");
        if (requested != null) {
            sessionId = requested;
            Db.Session session = db.session(requested);
            title = session == null || session.title.isEmpty() ? "对话" : session.title;
            long settle = Diag.now();
            engine.settleInterrupted(requested);
            Diag.log("OPEN", "恢复会话 " + shortId(requested) + " · 结算中断 "
                + Diag.millis(settle) + "ms");
            loadMessages();
        }
        titleView.setText(title);
        if (engine.isStreaming()) main.post(streamWatcher);
        paintToggles();
        updateWelcome();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("session", sessionId);
        if (pendingCapture != null) state.putString("camera_uri", pendingCapture.toString());
    }

    /** 主题上色。 */
    private void applyTheme() {
        findViewById(android.R.id.content).setBackgroundColor(palette.bg);
        list.setBackgroundColor(palette.bg);
        GradientDrawable composer = new GradientDrawable();
        composer.setColor(palette.composer);
        composer.setCornerRadius(dp(24));
        composer.setStroke(Math.max(1, (int) dp(1)), palette.strokeThin);
        ((View) input.getParent()).setBackground(composer);
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.textDim);
        titleView.setTextColor(palette.text);
        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setShape(GradientDrawable.OVAL);
        sendBackground.setColor(palette.accent);
        sendButton.setBackground(sendBackground);
        drawer.setBackgroundColor(palette.surface);
    }

    private void paintToggles() {
        paintPill(thinkingButton, thinking);
        paintPill(searchButton, search);
    }

    private void paintPill(TextView view, boolean active) {
        view.setBackgroundResource(active ? R.drawable.pill_active
            : palette.dark ? R.drawable.pill : R.drawable.pill_light);
        view.setTextColor(active ? palette.accent : palette.textDim);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(streamWatcher);
        thumbnails.shutdownNow();
        thumbnailCache.evictAll();
        if (engine != null) engine.close();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ================================================================ Engine 回调

    @Override
    public void onReply(Msg snapshot) {
        int index = indexOf(snapshot.id);
        if (index < 0) {
            return;
        }
        messages.set(index, snapshot);
        long since = Diag.now();
        // 流式更新只重绑可见的当前回答，避免每个分片重建所有可见历史。
        int position = positionOf(index);
        int first = list.getFirstVisiblePosition();
        int last = list.getLastVisiblePosition();
        if (position >= first && position <= last) {
            View row = list.getChildAt(position - first);
            if (row != null) {
                boolean follow = position == adapter.getCount() - 1
                    && row.getBottom() <= list.getHeight() - list.getPaddingBottom() + dp(24);
                adapter.getView(position, row, list);
                if (follow) list.post(this::scrollToBottom);
            }
        }
        long cost = Diag.millis(since);
        if (cost > 60) {
            Diag.log("UI", "流式重绑 " + cost + "ms · 第 " + index + "/" + messages.size()
                + " 条 · 正文 " + snapshot.replyText().length() + " 字");
        }
    }

    @Override
    public void onTitle(String generated) {
        title = generated;
        titleView.setText(title);
    }

    @Override
    public void onDone(Msg settled) {
        siblingCache.clear();
        int index = indexOf(settled.id);
        if (index >= 0) {
            messages.set(index, settled);
        }
        adapter.notifyDataSetChanged();
        updateSendButton();
        refreshDrawer();
        updateWelcome();
        updateContextBar();
        Diag.log("DONE", "第 " + index + " 条结算为 " + settled.status
            + " · 正文 " + settled.replyText().length() + " 字 · " + Diag.heap());
    }

    @Override
    public void onFilesChanged() {
        refreshAttachments();
    }

    // ================================================================ 会话与消息

    private void loadMessages() {
        long since = Diag.now();
        siblingCache.clear();
        messages.clear();
        if (sessionId != null) {
            messages.addAll(db.visibleMessages(sessionId));
        }
        hidden = Math.max(0, messages.size() - PAGE);
        adapter.notifyDataSetChanged();
        updateWelcome();
        updateContextBar();
        scrollToBottom();
        Diag.log("LOAD", "会话 " + shortId(sessionId) + " 可见 " + messages.size()
            + " 条 · " + Diag.millis(since) + "ms · " + Diag.heap());
    }

    static String shortId(String id) {
        if (id == null) {
            return "-";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private void scrollToBottom() {
        if (!messages.isEmpty() && adapter != null && adapter.getCount() > 0) {
            list.setSelection(adapter.getCount() - 1);
        }
    }

    private int indexOf(String id) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id.equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void updateWelcome() {
        boolean empty = messages.isEmpty();
        welcome.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        ((TextView) findViewById(R.id.greeting)).setText(greeting);
    }

    private void updateSendButton() {
        sendButton.setImageResource(engine.isStreaming() ? R.drawable.ic_stop
            : R.drawable.ic_arrow_up);
    }

    private void openSettings() {
        if (engine.isStreaming()) {
            Toast.makeText(this, "请先停止生成再打开设置", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null && adapter != null && sessionId != null && !engine.isStreaming()) {
            if (db.session(sessionId) == null) {
                sessionId = null;
                title = "对话";
                titleView.setText(title);
            }
            loadMessages();
        }
    }

    private void startNewChat() {
        if (engine.isStreaming()) {
            Toast.makeText(this, "正在生成，先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        sessionId = null;
        title = "对话";
        titleView.setText(title);
        messages.clear();
        attachments.clear();
        adapter.notifyDataSetChanged();
        refreshAttachments();
        updateWelcome();
    }

    private void switchTo(String id) {
        if (engine.isStreaming()) {
            Toast.makeText(this, "正在生成，先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        sessionId = id;
        Db.Session session = db.session(id);
        title = session == null || session.title.isEmpty() ? "对话" : session.title;
        titleView.setText(title);
        attachments.clear();
        refreshAttachments();
        engine.settleInterrupted(id);
        loadMessages();
    }

    private void refreshDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) {
            return;
        }
        drawerAdapter.sessions.clear();
        drawerAdapter.sessions.addAll(db.sessions());
        drawerAdapter.notifyDataSetChanged();
    }

    // ================================================================ 发送

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && attachments.isEmpty()) {
            return;
        }
        if (settings.apiKey().isEmpty()) {
            Toast.makeText(this, "先到设置里填 API Key", Toast.LENGTH_LONG).show();
            return;
        }
        List<Msg.FileAtt> files = new ArrayList<>(attachments);
        boolean ok = engine.send(sessionId, text, files, thinking, search);
        if (!ok) {
            return;
        }
        if (engine.activeSession() != null) {
            sessionId = engine.activeSession();
        }
        attachments.clear();
        refreshAttachments();
        input.setText("");
        loadMessages();
        updateSendButton();
    }

    // ================================================================ 附件

    /** Recent previews are optional; the system picker remains available without permission. */
    private void chooseAttachment() {
        final android.app.Dialog sheet = new android.app.Dialog(this);
        attachmentSheet = sheet;
        final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(20));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        panel.setBackground(background);

        TextView heading = new TextView(this);
        heading.setText("最近图片");
        heading.setTextColor(palette.text);
        heading.setTextSize(16);
        heading.setPadding(dp(4), dp(8), dp(4), dp(12));
        panel.addView(heading);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(previews);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(112)));
        if (canReadRecentImages()) {
            loadRecentImages(sheet, scroll, previews, worker);
        } else {
            TextView permission = new TextView(this);
            permission.setText("允许访问照片以显示最近图片");
            permission.setTextColor(palette.text);
            permission.setGravity(Gravity.CENTER);
            previews.addView(permission, new LinearLayout.LayoutParams(dp(260), dp(104)));
            permission.setOnClickListener(v -> requestRecentImages());
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(16), 0, 0);
        String[] labels = {"📷\n拍照", "🖼\n相册", "📄\n文件"};
        for (int i = 0; i < labels.length; i++) {
            final int choice = i;
            TextView item = new TextView(this);
            item.setText(labels[i]);
            item.setTextColor(palette.text);
            item.setTextSize(16);
            item.setGravity(Gravity.CENTER);
            actions.addView(item, new LinearLayout.LayoutParams(0, dp(72), 1));
            item.setOnClickListener(v -> {
                sheet.dismiss();
                if (choice == 0) takePhoto();
                else if (choice == 1) pickImages();
                else pickFiles();
            });
        }
        panel.addView(actions);
        sheet.setOnDismissListener(dialog -> {
            if (attachmentSheet == sheet) attachmentSheet = null;
            worker.shutdownNow();
        });
        sheet.setContentView(panel);
        android.view.Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
        }
        sheet.show();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private boolean canReadRecentImages() {
        if (android.os.Build.VERSION.SDK_INT >= 34 && checkSelfPermission(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        String permission = android.os.Build.VERSION.SDK_INT >= 33
            ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE";
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecentImages() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQUEST_RECENT_IMAGES);
        } else {
            requestPermissions(new String[]{android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE"}, REQUEST_RECENT_IMAGES);
        }
    }

    private void loadRecentImages(android.app.Dialog sheet, android.widget.HorizontalScrollView scroll,
                                  LinearLayout previews, java.util.concurrent.ExecutorService worker) {
        worker.execute(() -> {
            List<android.net.Uri> images = new ArrayList<>();
            try (android.database.Cursor cursor = getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Images.Media._ID}, null, null,
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) while (images.size() < 40 && cursor.moveToNext()) {
                    images.add(android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
                }
            } catch (RuntimeException ignored) { /* Permission may have been revoked. */ }
            main.post(() -> {
                if (!sheet.isShowing()) return;
                if (images.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText("没有可预览的最近图片，可从相册选择");
                    empty.setTextColor(palette.text);
                    empty.setGravity(Gravity.CENTER);
                    previews.addView(empty, new LinearLayout.LayoutParams(dp(260), dp(104)));
                    return;
                }
                for (android.net.Uri uri : images) {
                    ImageView image = new ImageView(this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(0xffdddddd);
                    image.setContentDescription("最近图片");
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(104));
                    lp.setMargins(0, 0, dp(8), 0);
                    previews.addView(image, lp);
                    image.setOnClickListener(v -> {
                        sheet.dismiss();
                        if (attachments.size() >= Attachments.MAX_COUNT) {
                            Toast.makeText(this, "附件数量已达上限", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            attachments.add(engine.attachments().importUri(uri));
                            refreshAttachments();
                        } catch (Attachments.Rejected error) {
                            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
                Runnable update = () -> updateRecentThumbnails(sheet, scroll, previews, images, worker);
                scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> update.run());
                scroll.post(update);
            });
        });
    }

    private void updateRecentThumbnails(android.app.Dialog sheet, android.widget.HorizontalScrollView scroll,
                                        LinearLayout previews, List<android.net.Uri> images,
                                        java.util.concurrent.ExecutorService worker) {
        if (!sheet.isShowing() || worker.isShutdown()) return;
        int left = scroll.getScrollX(), right = left + scroll.getWidth();
        for (int i = 0; i < images.size(); i++) {
            ImageView view = (ImageView) previews.getChildAt(i);
            if (view.getRight() <= left || view.getLeft() >= right) {
                view.setTag(null);
                view.setImageDrawable(null);
                continue;
            }
            if (view.getTag() != null) continue;
            Object token = new Object();
            view.setTag(token);
            android.net.Uri uri = images.get(i);
            worker.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        bitmap = getContentResolver().loadThumbnail(uri, new android.util.Size(dp(104), dp(104)), null);
                    } else {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                            BitmapFactory.decodeStream(in, null, options);
                        }
                        options.inSampleSize = 1;
                        while (options.outWidth / options.inSampleSize > dp(208)
                                || options.outHeight / options.inSampleSize > dp(208)) options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                            bitmap = BitmapFactory.decodeStream(in, null, options);
                        }
                    }
                } catch (Exception | OutOfMemoryError ignored) { }
                Bitmap result = bitmap;
                main.post(() -> {
                    if (sheet.isShowing() && view.getTag() == token) view.setImageBitmap(result);
                    else if (result != null) result.recycle();
                });
            });
        }
    }

    private void takePhoto() {
        if (attachments.size() >= Attachments.MAX_COUNT) {
            Toast.makeText(this, "附件数量已达上限", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "capture_" + java.util.UUID.randomUUID().toString().replace("-", "") + ".jpg";
        pendingCapture = new android.net.Uri.Builder().scheme("content")
            .authority("com.nativechat.camera").appendPath(name).build();
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCapture);
        intent.setClipData(ClipData.newRawUri("camera", pendingCapture));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            pendingCapture = null;
            Toast.makeText(this, "无法打开相机，请检查是否安装相机应用", Toast.LENGTH_LONG).show();
        }
    }

    /** 相册/图片选择器：只列图片，选完就是图片请求。 */
    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg",
            "image/gif", "image/webp", "image/bmp"});
        startActivityForResult(intent, REQUEST_IMAGES);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA) {
            android.net.Uri captured = pendingCapture;
            pendingCapture = null;
            if (captured != null) {
                File temporary = new File(getCacheDir(), captured.getLastPathSegment());
                try {
                    if (resultCode == RESULT_OK && temporary.isFile() && temporary.length() > 0) {
                        attachments.add(engine.attachments().importUri(captured));
                        refreshAttachments();
                    } else if (resultCode == RESULT_OK) {
                        Toast.makeText(this, "相机没有返回照片，请重试", Toast.LENGTH_LONG).show();
                    }
                } catch (Attachments.Rejected error) {
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                } finally {
                    revokeUriPermission(captured, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    temporary.delete();
                }
            }
            return;
        }
        if ((requestCode != REQUEST_FILES && requestCode != REQUEST_IMAGES)
            || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<android.net.Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                uris.add(data.getClipData().getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (android.net.Uri uri : uris) {
            if (attachments.size() >= Attachments.MAX_COUNT) {
                Toast.makeText(this, "最多 " + Attachments.MAX_COUNT + " 个附件",
                    Toast.LENGTH_LONG).show();
                break;
            }
            try {
                attachments.add(engine.attachments().importUri(uri));
            } catch (Attachments.Rejected error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        refreshAttachments();
    }

    private void refreshAttachments() {
        if (attachments.isEmpty()) {
            attachmentsLabel.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Msg.FileAtt file : attachments) {
            if (text.length() > 0) {
                text.append("   ");
            }
            text.append("📎 ").append(file.name).append("（")
                .append(Util.formatSize(file.size)).append("）");
        }
        text.append("    点此清空");
        attachmentsLabel.setText(text.toString());
        attachmentsLabel.setVisibility(View.VISIBLE);
    }

    // ================================================================ 抽屉

    /** 点开才读盘解码；列表里不留任何位图。 */
    private void showImage(Msg.FileAtt file) {
        File target = new Attachments(this).local(file);
        if (target == null) {
            Toast.makeText(this, "这张图的原件不在本机", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView view = new ImageView(this);
        view.setAdjustViewBounds(true);
        view.setMinimumHeight(dp(120));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create();
        dialog.setOnDismissListener(closed -> release(view));
        dialog.show();
        final int max = Math.max(getResources().getDisplayMetrics().widthPixels,
            getResources().getDisplayMetrics().heightPixels);
        new Thread(() -> {
            Bitmap bitmap = decode(target, max);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                if (bitmap == null) {
                    dialog.dismiss();
                    Toast.makeText(this, "图片打不开（可能太大）", Toast.LENGTH_SHORT).show();
                } else {
                    view.setImageBitmap(bitmap);
                }
            });
        }, "image-preview").start();
    }

    private static void release(ImageView view) {
        android.graphics.drawable.Drawable drawable = view.getDrawable();
        view.setImageDrawable(null);
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    /**
     * 按屏幕尺寸采样解码，并按可用内存再降一档；超大图不会一次吃掉整个堆。
     * OOM 时加倍采样重试，实在不行返回 null。
     */
    private static Bitmap decode(File file, int max) {
        String path = file.getAbsolutePath();
        int forced = 1;
        while (forced <= 16) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int sample = 1;
                while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = Math.max(sample, forced);
                long pixels = (long) (bounds.outWidth / options.inSampleSize)
                    * (bounds.outHeight / options.inSampleSize);
                long budget = Runtime.getRuntime().maxMemory() / 8;
                while (pixels * 4 > budget) {
                    options.inSampleSize *= 2;
                    pixels /= 4;
                }
                return BitmapFactory.decodeFile(path, options);
            } catch (OutOfMemoryError error) {
                forced = Math.max(2, forced * 2);
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private void showDrawer() {
        drawerAdapter.sessions.clear();
        drawerAdapter.sessions.addAll(db.sessions());
        drawerAdapter.notifyDataSetChanged();
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        drawer.setTranslationX(drawer.getWidth() == 0 ? -dp(300) : -drawer.getWidth());
        drawer.animate().translationX(0f).setDuration(180).start();
        scrim.animate().alpha(1f).setDuration(180).start();
    }

    private void hideDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) {
            return;
        }
        drawer.animate().translationX(-dp(300)).setDuration(160).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            drawer.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
        }).start();
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) {
            hideDrawer();
            return;
        }
        super.onBackPressed();
    }

    private void sessionActions(Db.Session session) {
        new AlertDialog.Builder(this)
            .setItems(new String[]{"重命名", "删除"}, (dialog, which) -> {
                if (which == 0) {
                    EditText field = new EditText(this);
                    field.setText(session.title);
                    field.setSelection(field.getText().length());
                    int pad = dp(20);
                    LinearLayout box = new LinearLayout(this);
                    box.setPadding(pad, pad, pad, 0);
                    box.addView(field);
                    new AlertDialog.Builder(this)
                        .setTitle("重命名")
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", (dialog2, button) -> {
                            String name = field.getText().toString().trim();
                            if (!name.isEmpty()) {
                                db.renameSession(session.id, name);
                                if (session.id.equals(sessionId)) {
                                    title = name;
                                    titleView.setText(name);
                                }
                            }
                        })
                        .show();
                } else {
                    new AlertDialog.Builder(this)
                        .setMessage("删除「" + session.title + "」？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog2, button) -> {
                            db.deleteSession(session.id);
                            if (session.id.equals(sessionId)) {
                                startNewChat();
                            }
                            refreshDrawer();
                        })
                        .show();
                }
            })
            .show();
    }

    // ================================================================ 消息操作

    private void showActions(Msg message) {
        if (message.isUser()) {
            new AlertDialog.Builder(this)
                .setItems(new String[]{"复制", "编辑并重发"}, (dialog, which) -> {
                    if (which == 0) {
                        copy(message.content);
                    } else {
                        edit(message);
                    }
                })
                .show();
            return;
        }
        List<String> items = new ArrayList<>();
        items.add("复制");
        items.add("朗读");
        items.add("重新生成");
        if (Prompt.canContinueReply(message)) {
            items.add("继续生成");
        }
        if (message.error != null && Api.UPLOAD.equals(message.error.optString("kind"))) {
            items.add("重试上传附件");
        }
        new AlertDialog.Builder(this)
            .setItems(items.toArray(new String[0]), (dialog, which) -> {
                String choice = items.get(which);
                if ("复制".equals(choice)) {
                    copy(replyText(message));
                } else if ("朗读".equals(choice)) {
                    speak(replyText(message));
                } else if ("重新生成".equals(choice)) {
                    engine.regenerate(sessionId, message.id);
                    loadMessages();
                    updateSendButton();
                } else if ("继续生成".equals(choice)) {
                    engine.continueReply(sessionId, message.id);
                    loadMessages();
                    updateSendButton();
                } else if ("重试上传附件".equals(choice)) {
                    engine.retryUpload(sessionId, message.id);
                    loadMessages();
                    updateSendButton();
                }
            })
            .show();
    }

    private void edit(Msg message) {
        if (engine.isStreaming()) {
            Toast.makeText(this, "正在生成，先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText field = new EditText(this);
        field.setText(message.content);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this).setTitle("编辑提问").setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送", (dialog, which) -> {
                if (engine.edit(sessionId, message.id, field.getText().toString())) {
                    loadMessages();
                    updateSendButton();
                }
            }).show();
    }

    private void cycleVersion(Msg message) {
        List<Msg> siblings = db.repliesTo(sessionId, message.parentId);
        if (siblings.size() <= 1) {
            return;
        }
        int current = 0;
        for (int index = 0; index < siblings.size(); index++) {
            if (siblings.get(index).id.equals(message.id)) {
                current = index;
                break;
            }
        }
        Msg next = siblings.get((current + 1) % siblings.size());
        engine.selectVersion(sessionId, next.id);
        loadMessages();
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void speak(String text) {
        if (tts == null || text.isEmpty()) {
            return;
        }
        tts.setLanguage(Locale.CHINA);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message");
    }

    private String replyText(Msg message) {
        return message.isUser() ? message.content : message.replyText();
    }

    // ================================================================ 语音输入

    private void toggleVoice() {
        if (listening) {
            stopVoice();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        startVoice();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_RECENT_IMAGES && attachmentSheet != null && attachmentSheet.isShowing()) {
            attachmentSheet.dismiss();
            chooseAttachment();
        }
        if (requestCode == REQUEST_AUDIO && results.length > 0
            && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoice();
        }
    }

    private void startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "本机没有可用的语音识别服务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    listening = false;
                    ((ImageView) findViewById(R.id.mic)).setAlpha(1f);
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    ((ImageView) findViewById(R.id.mic)).setAlpha(1f);
                    String text = firstResult(results);
                    if (!text.isEmpty()) {
                        input.setText(voiceBase + text);
                        input.setSelection(input.getText().length());
                    }
                }

                @Override
                public void onPartialResults(Bundle partial) {
                    String text = firstResult(partial);
                    if (!text.isEmpty()) {
                        input.setText(voiceBase + text);
                        input.setSelection(input.getText().length());
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
        voiceBase = input.getText().toString();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
        listening = true;
        ((ImageView) findViewById(R.id.mic)).setAlpha(0.45f);
    }

    private void stopVoice() {
        if (recognizer != null) {
            recognizer.stopListening();
        }
        listening = false;
        ((ImageView) findViewById(R.id.mic)).setAlpha(1f);
    }

    private String firstResult(Bundle bundle) {
        List<String> texts = bundle == null ? null
            : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return texts == null || texts.isEmpty() ? "" : texts.get(0);
    }

    // ================================================================ 渲染

    private float fontSize() {
        int level = settings.font();
        return level == 0 ? 13f : level == 2 ? 17f : 15f;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private final class DrawerAdapter extends BaseAdapter {
        final List<Db.Session> sessions = new ArrayList<>();
        final List<Db.Hit> hits = new ArrayList<>();
        String query = "";

        private boolean searching() {
            return !query.isEmpty();
        }

        @Override
        public int getCount() {
            return searching() ? hits.size() : sessions.size();
        }

        @Override
        public Object getItem(int position) {
            return searching() ? hits.get(position) : sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                : getLayoutInflater().inflate(R.layout.item_session, parent, false);
            TextView name = view.findViewById(R.id.title);
            TextView preview = view.findViewById(R.id.subtitle);
            name.setTextColor(palette.text);
            preview.setTextColor(palette.textDim);
            if (searching()) {
                Db.Hit hit = hits.get(position);
                name.setText(hit.title == null || hit.title.isEmpty() ? "未命名会话" : hit.title);
                preview.setText(hit.titleMatch ? "标题命中" : hit.snippet);
                return view;
            }
            Db.Session session = sessions.get(position);
            name.setText(session.title == null || session.title.isEmpty() ? "新对话" : session.title);
            String text = db.lastMessagePreview(session.id);
            if (text.length() > 24) {
                text = text.substring(0, 24) + "…";
            }
            preview.setText(text);
            return view;
        }
    }

    // ================================================================ 搜索

    /** 输入停顿后再查，避免每敲一个字都扫一遍库。 */
    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch(drawerAdapter.query);
        }
    };

    private void onSearchChanged(String text) {
        drawerAdapter.query = text == null ? "" : text.trim();
        main.removeCallbacks(searchTask);
        if (drawerAdapter.query.isEmpty()) {
            drawerAdapter.hits.clear();
            drawerAdapter.notifyDataSetChanged();
            return;
        }
        main.postDelayed(searchTask, 220);
    }

    private void runSearch(final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        new Thread(() -> {
            List<Db.Hit> result = new ArrayList<>();
            try {
                result.addAll(db.search(keyword, 40));
            } catch (Exception error) {
                Diag.log("SEARCH", "失败 · " + error);
            }
            final List<Db.Hit> found = result;
            runOnUiThread(() -> {
                if (!keyword.equals(drawerAdapter.query)) {
                    return;   // 已经输到别的词了，丢弃这次结果
                }
                drawerAdapter.hits.clear();
                drawerAdapter.hits.addAll(found);
                drawerAdapter.notifyDataSetChanged();
                Diag.log("SEARCH", "\"" + keyword + "\" 命中 " + found.size() + " 条");
            });
        }, "search").start();
    }

    private final class MessageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            // 只渲染最近的窗口；更早的消息按需“载入更早”，避免一次布局上千条。
            return messages.size() - hidden + (hidden > 0 ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            int index = indexAt(position);
            return index < 0 ? null : messages.get(index);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) (convertView != null ? convertView
                : getLayoutInflater().inflate(R.layout.item_message, parent, false));
            row.setTag(new Object()); // Invalidate asynchronous work for the previous binding.
            row.removeAllViews();
            row.setGravity(Gravity.START);
            int index = indexAt(position);
            if (index < 0) {
                TextView more = new TextView(ChatActivity.this);
                more.setText("载入更早的 " + Math.min(hidden, PAGE) + " 条");
                more.setTextSize(12f);
                more.setTextColor(palette.caption);
                more.setGravity(Gravity.CENTER);
                more.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Color.TRANSPARENT);
                pill.setCornerRadius(dp(999));
                pill.setStroke(Math.max(1, (int) dp(1)), palette.stroke);
                more.setBackground(pill);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.CENTER_HORIZONTAL;
                params.topMargin = (int) dp(8);
                params.bottomMargin = (int) dp(12);
                more.setLayoutParams(params);
                more.setOnClickListener(view -> loadEarlier());
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.addView(more);
                return row;
            }
            long since = Diag.now();
            Msg message = messages.get(index);
            if (message.isUser()) {
                bindUser(row, message);
            } else {
                bindAssistant(row, message);
            }
            long cost = Diag.millis(since);
            if (cost > 80) {
                Diag.log("BIND", "第 " + index + "/" + messages.size() + " 条耗时 " + cost
                    + "ms · " + (message.isUser() ? "提问 " + message.content.length() + " 字"
                    : "回答 " + message.blocks.size() + " 块 " + message.replyText().length() + " 字"));
            }
            return row;
        }
    }

    /** position → messages 下标；返回 -1 表示这一行是“载入更早”。 */
    private int indexAt(int position) {
        if (hidden <= 0) {
            return position;
        }
        return position == 0 ? -1 : hidden + position - 1;
    }

    private int positionOf(int index) {
        return hidden <= 0 ? index : index - hidden + 1;
    }

    // ================================================================ 上下文用量

    /** 最近一次请求实际占用的 token：输入 + 缓存读 + 缓存写 + 输出。 */
    private long usedTokens() {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.isUser() || message.usage == null) {
                continue;
            }
            long tokens = Prompt.contextTokens(message.usage);
            if (tokens > 0) {
                return tokens;
            }
        }
        return 0;
    }

    private void updateContextBar() {
        if (contextBar == null) {
            return;
        }
        long used = usedTokens();
        if (used <= 0 || sessionId == null) {
            contextBar.setVisibility(View.GONE);
            return;
        }
        int limit = settings.contextLimit();
        long left = Math.max(0, limit - used);
        int percent = (int) Math.min(100, used * 100 / Math.max(1, limit));
        StringBuilder text = new StringBuilder();
        text.append("上下文 ").append(kTokens(used)).append(" / ").append(kTokens(limit))
            .append(" · 剩 ").append(percent >= 100 ? "0" : String.valueOf(100 - percent))
            .append("%（").append(kTokens(left)).append("）");
        Compactor.Summary summary = Compactor.load(this, sessionId);
        if (summary != null) {
            text.append(" · 已压缩 ").append(summary.rounds).append(" 轮");
        }
        contextBar.setText(text.toString());
        contextBar.setTextColor(percent >= 85 ? Color.parseColor("#FF6B6B")
            : percent >= 65 ? Color.parseColor("#E0A030") : palette.textDim);
        contextBar.setVisibility(View.VISIBLE);
    }

    private void showContextDetail() {
        long used = usedTokens();
        int limit = settings.contextLimit();
        Compactor.Summary summary = sessionId == null ? null : Compactor.load(this, sessionId);
        StringBuilder text = new StringBuilder();
        text.append("本次已用：").append(kTokens(used)).append(" tokens\n");
        text.append("上限：").append(kTokens(limit)).append("\n");
        text.append("剩余：").append(kTokens(Math.max(0, limit - used))).append("\n\n");
        Msg last = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (!message.isUser() && message.usage != null) {
                last = message;
                break;
            }
        }
        if (last != null) {
            text.append("最近一次请求：\n").append(Prompt.usageText(last.usage)).append("\n\n");
        }
        if (summary != null) {
            text.append("已压缩 ").append(summary.rounds).append(" 轮历史，摘要 ")
                .append(summary.text.length()).append(" 字（").append(styleName(summary.style))
                .append("）\n");
        } else {
            text.append(settings.autoCompact()
                ? "自动压缩已开启，到 "
                + (settings.contextLimit() * 3 / 4 / 1000) + "K 时触发。\n"
                : "自动压缩已关闭，接近上限时请求会报错。\n");
        }
        new AlertDialog.Builder(this)
            .setTitle("上下文用量")
            .setMessage(text.toString())
            .setPositiveButton("好", null)
            .setNeutralButton("设置", (dialog, which) ->
                startActivity(new Intent(this, SettingsActivity.class)))
            .show();
    }

    private static String styleName(int style) {
        if (style == Compactor.STYLE_TECH) {
            return "技术型";
        }
        if (style == Compactor.STYLE_COMPANION) {
            return "陪伴型";
        }
        return "普通";
    }

    private static String kTokens(long tokens) {
        if (tokens >= 1000000) {
            return String.format(Locale.US, "%.1fM", tokens / 1000000.0);
        }
        if (tokens >= 1000) {
            return String.format(Locale.US, "%.1fK", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }

    /** 往前补一页，并保持当前阅读位置不跳。 */
    private void loadEarlier() {
        int before = hidden;
        hidden = Math.max(0, hidden - PAGE);
        if (hidden == before) {
            return;
        }
        adapter.notifyDataSetChanged();
        int anchor = before - hidden;
        if (anchor > 0 && anchor < adapter.getCount()) {
            list.setSelection(anchor);
        }
    }

    private void bindUser(LinearLayout row, Msg message) {
        row.setGravity(Gravity.END);
        // UserMessage.module.css: max-width: calc(100% - 68px)、padding 10px 16px、radius 22px
        int maxWidth = getResources().getDisplayMetrics().widthPixels - (int) dp(68);
        row.setPadding(0, 0, 0, (int) dp(16));
        for (Msg.FileAtt file : message.files) {
            View attachment;
            if (file.isImage) {
                ImageView image = new ImageView(this);
                int side = dp(128);
                image.setLayoutParams(new LinearLayout.LayoutParams(side, side));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(palette.bubbleUser);
                shape.setCornerRadius(dp(14));
                image.setBackground(shape);
                image.setClipToOutline(true);
                image.setContentDescription(file.name);
                image.setOnClickListener(view -> showImage(file));
                loadThumbnail(row, image, file, side);
                attachment = image;
            } else {
                LinearLayout card = new LinearLayout(this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(palette.bubbleUser);
                shape.setCornerRadius(dp(14));
                card.setBackground(shape);
                TextView icon = new TextView(this);
                icon.setText("📄");
                icon.setTextSize(22f);
                card.addView(icon);
                LinearLayout details = new LinearLayout(this);
                details.setOrientation(LinearLayout.VERTICAL);
                details.setPadding(dp(10), 0, 0, 0);
                TextView name = new TextView(this);
                name.setText(file.name);
                name.setTextColor(palette.text);
                name.setTextSize(14f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setMaxWidth(Math.max(dp(80), maxWidth - dp(70)));
                details.addView(name);
                TextView size = new TextView(this);
                size.setText(Util.formatSize(file.size));
                size.setTextColor(palette.textDim);
                size.setTextSize(12f);
                details.addView(size);
                card.addView(details);
                attachment = card;
            }
            LinearLayout.LayoutParams attachmentParams = new LinearLayout.LayoutParams(
                file.isImage ? dp(128) : ViewGroup.LayoutParams.WRAP_CONTENT,
                file.isImage ? dp(128) : ViewGroup.LayoutParams.WRAP_CONTENT);
            attachmentParams.gravity = Gravity.END;
            attachmentParams.bottomMargin = dp(6);
            row.addView(attachment, attachmentParams);
        }
        if (message.content != null && !message.content.isEmpty()) {
            TextView text = new TextView(this);
            text.setTextSize(16f);
            text.setTextColor(palette.text);
            text.setLineSpacing(dp(4), 1.25f);
            text.setText(message.content);
            text.setMaxWidth(maxWidth - dp(32));
            text.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(palette.bubbleUser);
            background.setCornerRadius(dp(22));
            text.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.END;
            row.addView(text, params);
        }
        row.addView(action("编辑", view -> edit(message)));
    }

    private void loadThumbnail(LinearLayout row, ImageView image, Msg.FileAtt file, int side) {
        File target = new Attachments(this).local(file);
        if (target == null) return;
        String key = target.getAbsolutePath() + ":" + target.lastModified() + ":" + side;
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        Object binding = row.getTag();
        java.lang.ref.WeakReference<LinearLayout> rowRef = new java.lang.ref.WeakReference<>(row);
        java.lang.ref.WeakReference<ImageView> imageRef = new java.lang.ref.WeakReference<>(image);
        thumbnails.execute(() -> {
            Bitmap bitmap = decode(target, side * 2);
            if (bitmap == null) return;
            main.post(() -> {
                LinearLayout currentRow = rowRef.get();
                ImageView currentImage = imageRef.get();
                if (isFinishing() || isDestroyed() || currentRow == null || currentImage == null
                    || currentRow.getTag() != binding || currentImage.getParent() != currentRow) {
                    bitmap.recycle();
                    return;
                }
                thumbnailCache.put(key, bitmap);
                currentImage.setImageBitmap(bitmap);
            });
        });
    }

    private void bindAssistant(LinearLayout row, Msg message) {
        row.setGravity(Gravity.START);

        // 1. 思考：默认收起，点标题展开
        for (JSONObject block : message.blocks) {
            if (!"thinking".equals(block.optString("type"))) {
                continue;
            }
            String thinking = block.optString("thinking", "");
            if (thinking.trim().isEmpty()) {
                continue;
            }
            boolean open = expanded.contains(message.id);
            TextView header = new TextView(this);
            header.setTextSize(12f);
            header.setTextColor(palette.textDim);
            header.setText((open ? "▾ " : "▸ ") + "已深度思考（用时 "
                + (block.has("elapsedSeconds") ? String.valueOf(block.optInt("elapsedSeconds"))
                : String.valueOf(Prompt.thinkingSeconds(block))) + " 秒）");
            header.setPadding(0, dp(2), 0, dp(2));
            final String id = message.id;
            header.setOnClickListener(view -> {
                if (!expanded.remove(id)) {
                    expanded.add(id);
                }
                adapter.notifyDataSetChanged();
            });
            row.addView(header);
            if (open) {
                TextView body = new TextView(this);
                body.setTextSize(13.5f);
                body.setTextColor(palette.textDim);
                body.setLineSpacing(dp(3), 1.2f);
                body.setText(thinking);
                body.setPadding(dp(8), dp(2), 0, dp(6));
                row.addView(body);
            }
        }

        // 2. 工具步骤（搜索 / 打开网页 / 页内查找 / 阅读链接）
        for (JSONObject step : toolSteps(message)) {
            TextView line = new TextView(this);
            line.setTextSize(fontSize() - 2f);
            line.setTextColor(palette.textDim);
            line.setText(step.optString("text"));
            line.setPadding(0, dp(1), 0, dp(1));
            final String url = step.optString("url");
            if (Util.isWebUrl(url)) {
                line.setTextColor(palette.accent);
                line.setOnClickListener(view -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception ignored) {
                    }
                });
            }
            row.addView(line);
        }

        // 3. 正文
        for (JSONObject block : message.blocks) {
            if (!"text".equals(block.optString("type")) || block.optBoolean("isTemplate", false)) {
                continue;
            }
            String text = block.optString("text", "");
            if (text.isEmpty()) {
                continue;
            }
            TextView body = new TextView(this);
            body.setTextSize(16f);
            body.setTextColor(palette.text);
            // --dsw-font-markdown-base: 16px/28px
            body.setLineSpacing(dp(4), 1.5f);
            if (Msg.PENDING.equals(message.status) || Msg.STREAMING.equals(message.status)) {
                // 生成时避免对不断增长的全文反复解析 Markdown；结束后恢复格式。
                body.setText(text);
            } else {
                body.setText(Markdown.render(text, palette.text,
                    palette.dark ? 0x1FFFFFFF : 0x14000000));
                body.setMovementMethod(LinkMovementMethod.getInstance());
            }
            row.addView(body);
        }

        // 4. 错误 / 拒绝 / 模板提示
        Describe.Notice notice = Describe.describeReply(message);
        if (notice != null) {
            TextView line = new TextView(this);
            line.setTextSize(14f);
            line.setTextColor(notice.response ? palette.text : palette.error);
            line.setLineSpacing(dp(2), 1.25f);
            if (notice.response) {
                line.setText(Markdown.render(notice.text, palette.text,
                    palette.dark ? 0x1FFFFFFF : 0x14000000));
            } else {
                line.setText("！ " + notice.text);
            }
            row.addView(line);
        }

        // 5. 正在生成 / 已停止 的占位
        boolean empty = message.replyText().isEmpty();
        if (empty && (Msg.PENDING.equals(message.status) || Msg.STREAMING.equals(message.status))) {
            TextView line = new TextView(this);
            line.setTextSize(fontSize());
            line.setTextColor(palette.textDim);
            line.setText("正在思考…");
            row.addView(line);
        } else if (empty && Msg.STOPPED.equals(message.status)) {
            TextView line = new TextView(this);
            line.setTextSize(fontSize());
            line.setTextColor(palette.textDim);
            line.setText(Describe.STOPPED_EMPTY);
            row.addView(line);
        }

        // 6. 底部：版本切换 + 用量 + 状态
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        String siblingKey = message.parentId == null ? "" : message.parentId;
        List<Msg> siblings = siblingCache.get(siblingKey);
        if (siblings == null) {
            siblings = db.repliesTo(sessionId, message.parentId);
            siblingCache.put(siblingKey, siblings);
        }
        if (siblings.size() > 1) {
            int at = 1;
            for (int index = 0; index < siblings.size(); index++) {
                if (siblings.get(index).id.equals(message.id)) {
                    at = index + 1;
                    break;
                }
            }
            TextView versions = new TextView(this);
            versions.setTextSize(11f);
            versions.setTextColor(palette.textDim);
            versions.setText(at + "/" + siblings.size());
            versions.setPadding(0, dp(4), dp(10), 0);
            versions.setOnClickListener(view -> cycleVersion(message));
            footer.addView(versions);
        }
        if (message.usage != null && Msg.DONE.equals(message.status)) {
            TextView usage = new TextView(this);
            usage.setTextSize(10.5f);
            usage.setTextColor(palette.textDim);
            usage.setLetterSpacing(0.02f);
            usage.setText(Prompt.usageText(message.usage));
            footer.addView(usage);
        } else if (Msg.STOPPED.equals(message.status)) {
            TextView stopped = new TextView(this);
            stopped.setTextSize(11f);
            stopped.setTextColor(palette.textDim);
            stopped.setText("已停止");
            footer.addView(stopped);
        }
        if ("max_tokens".equals(message.stopReason)) {
            TextView limited = new TextView(this);
            limited.setTextSize(11f);
            limited.setTextColor(palette.textDim);
            limited.setText("　已达长度上限，可继续生成");
            footer.addView(limited);
        }
        if (footer.getChildCount() > 0) {
            row.addView(footer);
        }
        LinearLayout actions = actionRow();
        actions.addView(action("复制", view -> copy(replyText(message))));
        actions.addView(action("重新生成", view -> regenerate(message)));
        if (Prompt.canContinueReply(message)) {
            actions.addView(action("继续生成", view -> {
                if (busyGuard()) {
                    return;
                }
                engine.continueReply(sessionId, message.id);
                loadMessages();
                updateSendButton();
            }));
        }
        if (message.error != null && Api.UPLOAD.equals(message.error.optString("kind"))) {
            actions.addView(action("重试上传", view -> {
                if (busyGuard()) {
                    return;
                }
                engine.retryUpload(sessionId, message.id);
                loadMessages();
                updateSendButton();
            }));
        }
        row.addView(actions);
    }

    private LinearLayout actionRow() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // AssistantMessage.module.css .bottom: padding-top 12px / padding-bottom 10px
        bar.setPadding(0, (int) dp(12), 0, (int) dp(10));
        return bar;
    }

    private TextView action(String label, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(13f);
        view.setTextColor(palette.caption);
        view.setPadding(0, (int) dp(6), (int) dp(10), (int) dp(6));
        view.setOnClickListener(click);
        return view;
    }

    private boolean busyGuard() {
        if (engine.isStreaming()) {
            Toast.makeText(this, "正在生成，先停止或等待完成", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    /** 重新生成：在同一个提问下生成新的回复版本。 */
    private void regenerate(Msg message) {
        if (busyGuard()) {
            return;
        }
        engine.regenerate(sessionId, message.id);
        loadMessages();
        updateSendButton();
    }

    /** 把工具块整理成一行行可读的步骤。 */
    private List<JSONObject> toolSteps(Msg message) {
        List<JSONObject> steps = new ArrayList<>();
        for (JSONObject block : message.blocks) {
            String type = block.optString("type");
            JSONObject input = block.optJSONObject("input");
            if ("server_tool_use".equals(type) && input != null) {
                String action = input.optString("action", "");
                JSONObject step = new JSONObject();
                try {
                    if ("search".equals(action)) {
                        JSONArray queries = input.optJSONArray("queries");
                        StringBuilder text = new StringBuilder("🔍 搜索：");
                        for (int at = 0; queries != null && at < queries.length(); at++) {
                            if (at > 0) {
                                text.append("、");
                            }
                            text.append(queries.optString(at));
                        }
                        step.put("text", text.toString());
                    } else if ("find_in_page".equals(action)) {
                        step.put("text", "🔎 页内查找：" + input.optString("pattern", ""));
                        step.put("url", input.optString("url", ""));
                    } else {
                        step.put("text", "🔗 阅读：" + input.optString("url", ""));
                        step.put("url", input.optString("url", ""));
                    }
                } catch (Exception ignored) {
                }
                steps.add(step);
            } else if ("web_search_tool_result".equals(type)) {
                JSONArray content = block.optJSONArray("content");
                int count = 0;
                boolean failed = false;
                for (int at = 0; content != null && at < content.length(); at++) {
                    JSONObject item = content.optJSONObject(at);
                    if (item == null) {
                        continue;
                    }
                    if ("web_search_tool_result_error".equals(item.optString("type"))) {
                        failed = true;
                    } else {
                        count++;
                    }
                }
                JSONObject step = new JSONObject();
                try {
                    step.put("text", failed ? "　搜索失败" : "　" + count + " 条结果");
                } catch (Exception ignored) {
                }
                steps.add(step);
            } else if ("web_fetch_tool_result".equals(type)) {
                JSONObject content = block.optJSONObject("content");
                boolean ok = content != null && "web_fetch_result".equals(content.optString("type"));
                JSONObject step = new JSONObject();
                try {
                    step.put("text", ok ? "　已读取" : "　读取失败");
                    if (ok) {
                        step.put("url", content.optString("url", ""));
                    }
                } catch (Exception ignored) {
                }
                steps.add(step);
            }
        }
        return steps;
    }
}


