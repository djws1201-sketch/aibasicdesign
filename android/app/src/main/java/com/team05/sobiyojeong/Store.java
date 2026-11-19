package com.team05.sobiyojeong;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small shared store between the web app (via Bridge) and the overlay service. */
final class Store {
    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("yojeong", Context.MODE_PRIVATE);
    }

    /** Queue an event (app opened, decision made) for the web app to pick up later. */
    static synchronized void push(Context c, JSONObject ev) {
        SharedPreferences p = prefs(c);
        JSONArray arr;
        try {
            arr = new JSONArray(p.getString("events", "[]"));
        } catch (JSONException e) {
            arr = new JSONArray();
        }
        arr.put(ev);
        p.edit().putString("events", arr.toString()).apply();
    }

    static synchronized String drain(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("events", "[]");
        p.edit().putString("events", "[]").apply();
        return s;
    }

    static synchronized int bumpOpens(Context c, String today) {
        SharedPreferences p = prefs(c);
        int n = today.equals(p.getString("opensDate", "")) ? p.getInt("opensN", 0) : 0;
        n++;
        p.edit().putString("opensDate", today).putInt("opensN", n).apply();
        return n;
    }

    static boolean isServiceOn(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(c.getPackageName() + "/");
    }
}
