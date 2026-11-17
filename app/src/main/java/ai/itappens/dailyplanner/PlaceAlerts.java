package ai.itappens.dailyplanner;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Registers one system proximity alert per saved place. */
final class PlaceAlerts {

    private static final String PREFS = "places";
    private static final String KEY_JSON = "json";
    private static final String KEY_IDS = "ids";
    static final String ACTION = "ai.itappens.dailyplanner.PLACE_ALERT";

    private PlaceAlerts() {
    }

    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, json).apply();
    }

    private static PendingIntent intentFor(Context c, String id, String name, String reminder) {
        Intent i = new Intent(c, PlaceAlertReceiver.class);
        i.setAction(ACTION);
        i.setData(Uri.parse("planner://place/" + Uri.encode(id)));
        i.putExtra("id", id);
        i.putExtra("name", name);
        i.putExtra("reminder", reminder);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // Proximity alerts add extras to the intent, so it must be mutable on Android 12+.
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags);
    }

    static void registerSaved(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        Set<String> old = new HashSet<>(sp.getStringSet(KEY_IDS, new HashSet<>()));
        for (String id : old) {
            PendingIntent p = intentFor(c, id, "", "");
            try {
                lm.removeProximityAlert(p);
            } catch (Exception ignored) {
            }
            p.cancel();
        }

        Set<String> now = new HashSet<>();
        boolean fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine) {
            try {
                JSONArray arr = new JSONArray(sp.getString(KEY_JSON, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id", "");
                    if (id.isEmpty() || !o.has("lat") || !o.has("lng")) continue;
                    float radius = (float) Math.max(50, o.optDouble("radius", 150));
                    try {
                        lm.addProximityAlert(o.getDouble("lat"), o.getDouble("lng"), radius, -1,
                                intentFor(c, id, o.optString("name", "Place"), o.optString("reminder", "")));
                        now.add(id);
                    } catch (SecurityException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        sp.edit().putStringSet(KEY_IDS, now).apply();
    }
}
