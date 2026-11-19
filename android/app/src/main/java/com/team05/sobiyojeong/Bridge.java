package com.team05.sobiyojeong;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Methods the web app can call as window.Android.* */
public class Bridge {
    private final Activity activity;

    Bridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String getApps() {
        PackageManager pm = activity.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || !seen.add(pkg)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", r.loadLabel(pm).toString());
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    @JavascriptInterface
    public String permStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("a11y", Store.isServiceOn(activity));
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void openAccessibilitySettings() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    @JavascriptInterface
    public void openAppInfo() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    @JavascriptInterface
    public void setConfig(String json) {
        Store.prefs(activity).edit().putString("config", json).apply();
    }

    @JavascriptInterface
    public String drainEvents() {
        return Store.drain(activity);
    }
}
