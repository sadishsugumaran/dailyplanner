package ai.itappens.dailyplanner;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily Planner & Journal — Made by Sadish (AI enabled).
 * Hosts the planner page in a WebView and gives it native GPS, file saving
 * and background place (arrival) alerts through the "Android" JS bridge.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOC = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_BG = 3;
    private static final int REQ_FILE = 4;

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> pendingLoc = new ArrayList<>();
    private ValueCallback<Uri[]> fileCb;

    private boolean enablingAlerts = false;
    private boolean askedNotif = false;
    private boolean askedFine = false;
    private boolean askedBg = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF4338CA);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setGeolocationEnabled(false); // location comes from the native bridge

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose backup file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }

        PlaceAlerts.registerSaved(this);
    }

    /* ------------------------------------------------------------------ */
    /* JS bridge                                                           */
    /* ------------------------------------------------------------------ */

    private class Bridge {
        @JavascriptInterface
        public void requestLocation(final String id) {
            main.post(() -> {
                if (!hasLocation()) {
                    pendingLoc.add(id);
                    if (pendingLoc.size() == 1) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                    }
                    return;
                }
                fetchLocation(id);
            });
        }

        @JavascriptInterface
        public void syncPlaces(final String json) {
            PlaceAlerts.save(MainActivity.this, json);
            main.post(() -> PlaceAlerts.registerSaved(MainActivity.this));
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            main.post(() -> doSave(name, mime, text));
        }

        @JavascriptInterface
        public void enableBackgroundAlerts() {
            main.post(() -> {
                enablingAlerts = true;
                askedNotif = false;
                askedFine = false;
                askedBg = false;
                continueEnable();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* Permissions                                                         */
    /* ------------------------------------------------------------------ */

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocation() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasBackground() {
        return Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void continueEnable() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            askedNotif = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !askedFine) {
            askedFine = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && !hasBackground() && !askedBg) {
            askedBg = true;
            jsToast("Choose 'Allow all the time' so alerts work when the app is closed");
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG);
            return;
        }
        finishEnable();
    }

    private void finishEnable() {
        enablingAlerts = false;
        PlaceAlerts.registerSaved(this);
        String msg;
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            msg = "Precise location is needed for place alerts";
        } else if (!hasBackground()) {
            msg = "Alerts on while the app is open. Set location to 'Allow all the time' in app settings for background alerts";
        } else if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            msg = "Notifications are off. Turn them on in app settings to get arrival alerts";
        } else {
            msg = "Background arrival alerts are on ✓";
        }
        jsToast(msg);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC && !pendingLoc.isEmpty()) {
            List<String> ids = new ArrayList<>(pendingLoc);
            pendingLoc.clear();
            for (String id : ids) {
                if (hasLocation()) fetchLocation(id);
                else deliver(id, null);
            }
        }
        if (requestCode == REQ_LOC) PlaceAlerts.registerSaved(this);
        if (enablingAlerts) continueEnable();
    }

    /* ------------------------------------------------------------------ */
    /* Location                                                            */
    /* ------------------------------------------------------------------ */

    private void fetchLocation(final String id) {
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        String provider = null;
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        }
        if (provider == null) {
            jsToast("Turn on Location in your phone settings");
            deliver(id, lastKnown(lm));
            return;
        }

        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            deliver(id, lastKnown(lm));
        };
        main.postDelayed(timeout, 20000);

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, getMainExecutor(), loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    deliver(id, loc != null ? loc : lastKnown(lm));
                });
            } else {
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override
                    public void onLocationChanged(Location loc) {
                        if (done[0]) return;
                        done[0] = true;
                        main.removeCallbacks(timeout);
                        deliver(id, loc);
                    }

                    @Override
                    public void onStatusChanged(String p, int status, Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String p) {
                    }

                    @Override
                    public void onProviderDisabled(String p) {
                    }
                }, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            done[0] = true;
            main.removeCallbacks(timeout);
            deliver(id, lastKnown(lm));
        }
    }

    private Location lastKnown(LocationManager lm) {
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private void deliver(String id, Location loc) {
        String js;
        if (loc == null) {
            js = "window.__nativeLoc&&window.__nativeLoc(" + JSONObject.quote(id) + ",0,0,0,true)";
        } else {
            js = String.format(Locale.US,
                    "window.__nativeLoc&&window.__nativeLoc(%s,%.6f,%.6f,%.1f,false)",
                    JSONObject.quote(id), loc.getLatitude(), loc.getLongitude(),
                    loc.hasAccuracy() ? loc.getAccuracy() : 0f);
        }
        web.evaluateJavascript(js, null);
    }

    /* ------------------------------------------------------------------ */
    /* Files                                                               */
    /* ------------------------------------------------------------------ */

    private void doSave(String name, String mime, String text) {
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("No file created");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException("No stream");
                    os.write(data);
                }
                jsToast("Saved to Downloads: " + name);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                jsToast("Saved: " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            jsToast("Couldn't save the file");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCb != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(result);
            fileCb = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    private void jsToast(String msg) {
        if (web != null) {
            web.evaluateJavascript("window.__nativeToast&&window.__nativeToast(" + JSONObject.quote(msg) + ")", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
