package com.nativechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 会话列表：新建、打开、长按删除、设置 API Key。 */
public class MainActivity extends Activity {
    static final String PREFS = Settings.PREFS;
    static final String KEY_API = Settings.KEY_API;
    static final String KEY_MODEL = Settings.KEY_MODEL;
    static final String KEY_FONT = Settings.KEY_FONT;
    static final String KEY_THEME = Settings.KEY_THEME;

    private final List<Db.Session> data = new ArrayList<>();
    private Db db;
    private SessionAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        setTheme(Palette.of(this).dark ? R.style.NativeChatDark : R.style.NativeChatLight);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Diag.start(this);
        db = new Db(this);

        Palette theme = Palette.of(this);
        findViewById(android.R.id.content).setBackgroundColor(theme.bg);
        ListView list = findViewById(R.id.list);
        list.setBackgroundColor(theme.bg);
        adapter = new SessionAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(data.get(position).id));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(data.get(position));
            return true;
        });

        findViewById(R.id.new_chat).setOnClickListener(view -> open(null));
        findViewById(R.id.settings).setOnClickListener(view ->
            startActivity(new android.content.Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(db.sessions());
        adapter.notifyDataSetChanged();
        findViewById(R.id.empty).setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(String sessionId) {
        Intent intent = new Intent(this, ChatActivity.class);
        if (sessionId != null) {
            intent.putExtra("session", sessionId);
        }
        startActivity(intent);
    }

    private void confirmDelete(Db.Session session) {
        Dialogs.builder(this)
            .setMessage("删除「" + session.title + "」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (dialog, which) -> {
                db.deleteSession(session.id);
                reload();
            })
            .show();
    }

    private final class SessionAdapter extends BaseAdapter {
        private final SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                : getLayoutInflater().inflate(R.layout.item_session, parent, false);
            Db.Session session = data.get(position);
            Palette palette = Palette.of(MainActivity.this);
            TextView titleView = view.findViewById(R.id.title);
            titleView.setTextColor(palette.text);
            ((TextView) view.findViewById(R.id.subtitle)).setTextColor(palette.textDim);
            view.setBackgroundColor(palette.bg);
            titleView.setText(session.title);
            String preview = db.lastMessagePreview(session.id);
            if (preview.length() > 40) {
                preview = preview.substring(0, 40) + "…";
            }
            String when = format.format(new Date(session.updatedAt));
            ((TextView) view.findViewById(R.id.subtitle)).setText(
                preview.isEmpty() ? when : preview + "   " + when);
            return view;
        }
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
