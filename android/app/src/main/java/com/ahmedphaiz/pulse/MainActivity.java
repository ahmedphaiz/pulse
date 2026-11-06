package com.ahmedphaiz.pulse;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulse app shell: shows the Pulse web app (same screens as the website) and adds native features
 * the web can't do: Google sign-in without hourly logouts, automatic Bluetooth heart rate in the
 * background, and a live heart-rate notification on the lock screen / always-on display.
 */
public class MainActivity extends Activity {
    static final String APP_URL = "https://ahmedphaiz.github.io/pulse/";
    static final String APP_HOST = "ahmedphaiz.github.io";
    private static final int REQ_AUTH = 41;
    private static final int REQ_PERMS = 42;

    static volatile MainActivity current;
    private WebView web;
    private Intent pendingWorkout;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D10);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D10);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Keep the web content clear of the status bar, navigation bar and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " PulseApp/1");
        web.addJavascriptInterface(new Bridge(), "PulseNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        if (b != null) web.restoreState(b); else web.loadUrl(APP_URL);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        js("window.onNativeResume&&onNativeResume()");
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }

    @Override protected void onDestroy() { if (current == this) current = null; web.destroy(); super.onDestroy(); }

    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        web.evaluateJavascript("window.onNativeBack?onNativeBack():false", r -> {
            if ("true".equals(r)) return;
            if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
        });
    }

    /** Run JavaScript in the web app (safe from any thread). */
    void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------- Google sign-in (Android authorization API: refreshes silently, no hourly popups) ----------
    private void authorize(String scopes) {
        List<Scope> list = new ArrayList<>();
        for (String sc : scopes.trim().split("\\s+")) if (!sc.isEmpty()) list.add(new Scope(sc));
        AuthorizationRequest req = AuthorizationRequest.builder().setRequestedScopes(list).build();
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener(res -> {
                if (res.hasResolution()) {
                    PendingIntent pi = res.getPendingIntent();
                    try { startIntentSenderForResult(pi.getIntentSender(), REQ_AUTH, null, 0, 0, 0); }
                    catch (Exception e) { tokenError(e.getMessage()); }
                } else deliverToken(res);
            })
            .addOnFailureListener(e -> tokenError(e.getMessage()));
    }

    private void deliverToken(AuthorizationResult res) {
        String t = res.getAccessToken();
        if (t == null) { tokenError("No access token returned"); return; }
        js("window.onNativeToken&&onNativeToken(" + JSONObject.quote(t) + ",3500)");
    }

    private void tokenError(String m) {
        js("window.onNativeTokenError&&onNativeTokenError(" + JSONObject.quote(m == null ? "Sign-in failed" : m) + ")");
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQ_AUTH) return;
        try { deliverToken(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)); }
        catch (Exception e) { tokenError(code == RESULT_CANCELED ? "Sign-in cancelled" : e.getMessage()); }
    }

    // ---------- Workout service ----------
    private void startWorkoutService(Intent i) {
        String[] perms = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS };
        List<String> missing = new ArrayList<>();
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) { pendingWorkout = i; requestPermissions(missing.toArray(new String[0]), REQ_PERMS); return; }
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_PERMS || pendingWorkout == null) return;
        boolean bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        if (bt) startForegroundService(pendingWorkout);
        else js("window.onNativeStatus&&onNativeStatus(false,'','Bluetooth permission denied')");
        pendingWorkout = null;
    }

    private void setWorkoutScreen(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setShowWhenLocked(on);   // workout screen can show over the lock screen
        });
    }

    /** Methods callable from the web app as window.PulseNative.* */
    class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }

        @JavascriptInterface public void signIn(String scopes) { runOnUiThread(() -> authorize(scopes)); }

        @JavascriptInterface public void startWorkout(String type, String label, int maxHr, long startMs) {
            Intent i = new Intent(MainActivity.this, HeartRateService.class)
                .setAction(HeartRateService.ACTION_START)
                .putExtra("type", type).putExtra("label", label)
                .putExtra("maxHr", maxHr).putExtra("start", startMs);
            runOnUiThread(() -> startWorkoutService(i));
        }

        @JavascriptInterface public void stopWorkout() {
            startService(new Intent(MainActivity.this, HeartRateService.class).setAction(HeartRateService.ACTION_STOP));
        }

        @JavascriptInterface public void forgetDevice() {
            getSharedPreferences("pulse", MODE_PRIVATE).edit().remove("addr").apply();
        }

        @JavascriptInterface public String samplesSince(double t) {
            JSONArray a = new JSONArray();
            for (long[] s : HeartRateService.samplesSince((long) t)) {
                JSONArray p = new JSONArray(); p.put(s[0]); p.put(s[1]); a.put(p);
            }
            return a.toString();
        }

        @JavascriptInterface public String status() {
            try {
                return new JSONObject().put("running", HeartRateService.running)
                    .put("connected", HeartRateService.connected)
                    .put("name", HeartRateService.deviceName == null ? "" : HeartRateService.deviceName)
                    .put("bpm", HeartRateService.lastBpm).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void keepScreenOn(boolean on) { setWorkoutScreen(on); }
    }
}
