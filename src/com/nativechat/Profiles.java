package com.nativechat;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模型配置档案：一条记录就是「接口地址 + Key + 模型」。
 * 存在 SharedPreferences 的 JSON 里，激活的那条决定实际请求参数。
 */
public final class Profiles {
    public static final String KEY_LIST = "profiles_v1";
    public static final String KEY_ACTIVE = "profiles_active";

    public static final class Profile {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String origin = Settings.DEFAULT_ORIGIN;
        public String apiKey = "";
        public String model = Prompt.DEFAULT_MODEL;

        public String display() {
            String title = name == null || name.trim().isEmpty() ? model : name.trim();
            return title + " · " + model;
        }
    }

    private Profiles() { }

    /** 读取全部档案；没有任何档案时用旧的单配置建一条。 */
    public static List<Profile> all(Settings settings) {
        SharedPreferences prefs = settings.raw();
        List<Profile> list = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, null);
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    Profile profile = new Profile();
                    profile.id = item.optString("id", profile.id);
                    profile.name = item.optString("name", "");
                    profile.origin = item.optString("origin", Settings.DEFAULT_ORIGIN);
                    profile.apiKey = item.optString("apiKey", "");
                    profile.model = item.optString("model", Prompt.DEFAULT_MODEL);
                    list.add(profile);
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            Profile profile = new Profile();
            profile.name = "默认";
            profile.origin = prefs.getString(Settings.KEY_ORIGIN, Settings.DEFAULT_ORIGIN);
            profile.apiKey = prefs.getString(Settings.KEY_API, "");
            profile.model = prefs.getString(Settings.KEY_MODEL, Prompt.DEFAULT_MODEL);
            list.add(profile);
            save(settings, list, profile.id);
        }
        return list;
    }

    public static Profile active(Settings settings) {
        List<Profile> list = all(settings);
        String id = settings.raw().getString(KEY_ACTIVE, "");
        for (Profile profile : list) {
            if (profile.id.equals(id)) return profile;
        }
        return list.get(0);
    }

    public static void save(Settings settings, List<Profile> list, String activeId) {
        JSONArray array = new JSONArray();
        for (Profile profile : list) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", profile.id);
                item.put("name", profile.name);
                item.put("origin", profile.origin);
                item.put("apiKey", profile.apiKey);
                item.put("model", profile.model);
            } catch (Exception ignored) {
            }
            array.put(item);
        }
        SharedPreferences.Editor editor = settings.raw().edit();
        editor.putString(KEY_LIST, array.toString());
        if (activeId != null) editor.putString(KEY_ACTIVE, activeId);
        editor.apply();
    }

    public static void activate(Settings settings, String id) {
        settings.raw().edit().putString(KEY_ACTIVE, id).apply();
    }
}
