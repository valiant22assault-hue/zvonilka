package io.github.zvonilka.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Окно приложения: та же звонилка, что на сайте, внутри WebView. Страница открывается по адресу сайта
 * (поэтому ссылки-приглашения ведут туда же), но, если сайт недоступен, берётся копия, встроенная в приложение.
 */
public class MainActivity extends Activity {
    static final String HOST = "valiant22assault-hue.github.io", BASE = "/zvonilka/";
    static final String HOME = "https://" + HOST + BASE;
    static final String ACTION_ACCEPT = "zv.ACCEPT", ACTION_OPEN = "zv.OPEN";
    static final String EXTRA_CALL = "callId", EXTRA_CONTACT = "contactId";
    private static final int REQ_START = 1, REQ_MEDIA = 2, REQ_RING = 3, REQ_MSG = 4;
    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "text/javascript");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("png", "image/png");
    }

    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    static volatile boolean visible;

    private WebView web;
    private SharedPreferences prefs;
    private boolean pageReady;
    private final List<String> pendingJs = new ArrayList<>();
    private String pendingAccept;
    private volatile boolean fastStart;
    private PermissionRequest pendingPerm;

    static Intent acceptIntent(Context ctx, String callId) {
        return new Intent(ctx, MainActivity.class).setAction(ACTION_ACCEPT).putExtra(EXTRA_CALL, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent openIntent(Context ctx, String contactId) {
        return new Intent(ctx, MainActivity.class).setAction(ACTION_OPEN).putExtra(EXTRA_CONTACT, contactId);
    }

    /** Выполнить код в странице, если окно открыто (из любого потока). */
    static void runJs(String code) {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> a.js(code));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        prefs = getSharedPreferences("zv", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xff5a5e66);
        getWindow().setNavigationBarColor(0xff4b4f56);
        web = new WebView(this);
        web.setBackgroundColor(0xff5a5e66);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "ZvonilkaApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        setContentView(web);

        Intent i = getIntent();
        fastStart = ACTION_ACCEPT.equals(i.getAction());   // отвечаем на звонок — не ждём сайт, берём встроенную копию
        handleAction(i);
        String hash = hashFrom(i);
        web.loadUrl(HOME + (hash != null ? hash : ""));
        ZvService.start(this);
        askPermissions();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAction(intent);
        String hash = hashFrom(intent);
        if (hash != null) js("location.hash = " + JSONObject.quote(hash));
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        ZvService s = ZvService.instance;
        if (s != null) s.clearMessages();
        // Вернулись (например, из системных настроек) — обновить настройки и историю звонков.
        js("window.zvApp && zvApp.resumed && zvApp.resumed()");
    }

    private boolean linkHidden() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = nm.getNotificationChannel("link");
        return !nm.areNotificationsEnabled() || (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE);
    }

    @Override protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (current.get() == this) current.clear();
        ZvService s = ZvService.instance;
        if (s != null) s.setInCall(false, "");
        web.destroy();
        super.onDestroy();
    }

    // «Назад» листает экраны звонилки; с главного — сворачивает приложение, не закрывая его (звонок не рвётся).
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.zvApp ? zvApp.back() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    private void js(String code) {
        if (pageReady) web.evaluateJavascript(code, null);
        else pendingJs.add(code);
    }

    private void handleAction(Intent i) {
        String a = i.getAction();
        if (ACTION_ACCEPT.equals(a)) {
            String callId = i.getStringExtra(EXTRA_CALL);
            showOverLock(true);
            IncomingCallActivity.close();
            ZvService s = ZvService.instance;
            if (s != null) s.ringDone(callId);
            if (pageReady) js("window.zvApp && zvApp.accept(" + JSONObject.quote(callId) + ")");
            else synchronized (this) { pendingAccept = callId; }
        } else if (ACTION_OPEN.equals(a)) {
            js("window.zvApp && zvApp.openContact(" + JSONObject.quote(i.getStringExtra(EXTRA_CONTACT)) + ")");
        }
    }

    // zvonilka://add/<приглашение> — кнопка «Открыть в приложении» на сайте; https-ссылки — если разрешены в настройках.
    private static String hashFrom(Intent i) {
        Uri u = i.getData();
        if (!Intent.ACTION_VIEW.equals(i.getAction()) || u == null) return null;
        if ("zvonilka".equals(u.getScheme()) && "add".equals(u.getHost()) && u.getPath() != null && u.getPath().length() > 1) {
            return "#add=" + u.getPath().substring(1);
        }
        if (isOurs(u) && u.getEncodedFragment() != null) return "#" + u.getEncodedFragment();
        return null;
    }

    private void showOverLock(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else {
            int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) getWindow().addFlags(f);
            else getWindow().clearFlags(f);
        }
    }

    static boolean isOurs(Uri u) {
        return "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith(BASE);
    }

    // ---------- разрешения при первом запуске ----------

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty() && !prefs.getBoolean("askedStart", false)) {
            prefs.edit().putBoolean("askedStart", true).apply();
            requestPermissions(need.toArray(new String[0]), REQ_START);
        } else {
            setupStep();
        }
    }

    // По очереди: работа в фоне без ограничений, затем звонок поверх экрана блокировки.
    private void setupStep() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Звонки, когда телефон спит")
                    .setMessage("Чтобы звонки и сообщения доходили, даже когда телефон заблокирован, разрешите звонилке работать в фоне без ограничений.")
                    .setPositiveButton("Разрешить", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {}
                    })
                    .setNegativeButton("Не сейчас", null)
                    .setOnDismissListener(d -> setupStep())
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager.class).canUseFullScreenIntent()
                && !prefs.getBoolean("askedFullScreen", false)) {
            prefs.edit().putBoolean("askedFullScreen", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Входящие звонки на весь экран")
                    .setMessage("Разрешите показывать входящий звонок на весь экран — как обычный звонок телефона.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {}
                    })
                    .setNegativeButton("Не сейчас", null)
                    .show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if ((code != REQ_RING && code != REQ_MSG) || result != RESULT_OK || data == null) return;
        Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);   // null — «Без звука»
        Sounds.set(this, code == REQ_RING ? Sounds.CALL : Sounds.MSG, u);
        js("window.zvApp && zvApp.soundsChanged && zvApp.soundsChanged()");
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_START) {
            ZvService.start(this);   // с разрешением на уведомления служба покажет своё
            setupStep();
        } else if (code == REQ_MEDIA && pendingPerm != null) {
            PermissionRequest req = pendingPerm;
            pendingPerm = null;
            List<String> ok = new ArrayList<>();
            for (String r : req.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && granted(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && granted(Manifest.permission.CAMERA)) ok.add(r);
            }
            if (ok.isEmpty()) req.deny();
            else req.grant(ok.toArray(new String[0]));
        }
    }

    // ---------- страница ----------

    private class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (isOurs(u)) return false;
            // Чужие ссылки из переписки — во внешнем браузере: страница звонилки не должна уходить, иначе оборвётся звонок.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {}
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!"GET".equals(r.getMethod()) || !isOurs(u)) return null;
            String name = u.getPath().substring(BASE.length());
            if (name.isEmpty()) name = "index.html";
            String mime = MIME.get(name.substring(name.lastIndexOf('.') + 1));
            if (mime == null || name.contains("/")) return null;
            byte[] body = fastStart ? null : fetch(u);
            // Страница с сайта годится, только если уже умеет работать с приложением (старая — нет).
            if (body != null && name.equals("index.html") && !new String(body, StandardCharsets.UTF_8).contains("window.ZvonilkaApp")) body = null;
            if (body == null) body = bundled(name);
            if (body == null) return null;
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            boolean text = !mime.startsWith("image/");
            return new WebResourceResponse(mime, text ? "utf-8" : null, 200, "OK", h, new ByteArrayInputStream(body));
        }

        @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            pageReady = false;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (!isOurs(Uri.parse(url))) return;
            pageReady = true;
            fastStart = false;
            for (String code : pendingJs) v.evaluateJavascript(code, null);
            pendingJs.clear();
            String id;
            synchronized (MainActivity.this) { id = pendingAccept; pendingAccept = null; }
            if (id != null) v.evaluateJavascript("window.zvApp && zvApp.accept(" + JSONObject.quote(id) + ")", null);
        }
    }

    private static byte[] fetch(Uri u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u.buildUpon().fragment(null).build().toString()).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            return c.getResponseCode() == 200 ? readAll(c.getInputStream()) : null;
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private byte[] bundled(String name) {
        try (InputStream in = getAssets().open("web/" + name)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    private class Chrome extends WebChromeClient {
        @Override public void onPermissionRequest(PermissionRequest req) {
            runOnUiThread(() -> {
                List<String> need = new ArrayList<>();
                for (String r : req.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                }
                if (need.isEmpty()) { req.grant(req.getResources()); return; }
                if (pendingPerm != null) pendingPerm.deny();
                pendingPerm = req;
                requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
            });
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest req) {
            if (pendingPerm == req) pendingPerm = null;
        }

        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w("zv-page", m.message() + " @" + m.lineNumber());
            return true;
        }
    }

    // ---------- мост: страница ↔ приложение (методы вызываются из потока WebView) ----------

    private class Bridge {
        /** Кто я, контакты с секретами пары, адреса реле — служба слушает их в фоне. */
        @JavascriptInterface public void sync(String json) {
            prefs.edit().putString("state", json).apply();
            ZvService s = ZvService.instance;
            if (s != null) s.stateChanged();
            else runOnUiThread(() -> ZvService.start(MainActivity.this));
        }

        @JavascriptInterface public void inCall(boolean on, String name) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showOverLock(false);
                }
                setVolumeControlStream(on ? AudioManager.STREAM_VOICE_CALL : AudioManager.USE_DEFAULT_STREAM_TYPE);
            });
            ZvService s = ZvService.instance;
            if (s != null) s.setInCall(on, name);
        }

        /** Страница сама ответила на звонок или отклонила его. */
        @JavascriptInterface public void ringDone(String callId) {
            ZvService s = ZvService.instance;
            if (s != null) s.ringDone(callId);
            IncomingCallActivity.close();
        }

        /** Звонок, на который нажали «Ответить» до того, как страница загрузилась. */
        @JavascriptInterface public String takeAccept() {
            synchronized (MainActivity.this) {
                String id = pendingAccept;
                pendingAccept = null;
                return id == null ? "" : id;
            }
        }

        /** Названия выбранных мелодии звонка и звука сообщений, вибрация. */
        @JavascriptInterface public String sounds() {
            try {
                return new JSONObject()
                        .put("call", Sounds.title(MainActivity.this, Sounds.get(MainActivity.this, Sounds.CALL)))
                        .put("msg", Sounds.title(MainActivity.this, Sounds.get(MainActivity.this, Sounds.MSG)))
                        .put("vibrate", Sounds.vibrate(MainActivity.this))
                        .put("linkHidden", linkHidden())
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Системный выбор мелодии: call — звонок, msg — сообщения. */
        @JavascriptInterface public void pickSound(String kind) {
            runOnUiThread(() -> {
                boolean call = "call".equals(kind);
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, call ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, call ? "Мелодия звонка" : "Звук сообщений")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Sounds.get(MainActivity.this, call ? Sounds.CALL : Sounds.MSG));
                try {
                    startActivityForResult(i, call ? REQ_RING : REQ_MSG);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "На этом телефоне нет выбора мелодий", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Системные настройки уведомления «на связи»: его можно выключить, служба продолжит работать. */
        @JavascriptInterface public void linkSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                            .putExtra(Settings.EXTRA_CHANNEL_ID, "link"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Не получилось открыть настройки уведомлений", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Пропущенные и отклонённые звонки, которые служба приняла без окна. */
        @JavascriptInterface public String takeCallLog() {
            return ZvService.takeCallLog(MainActivity.this);
        }

        @JavascriptInterface public void setVibrate(boolean on) {
            Sounds.setVibrate(MainActivity.this, on);
        }

        @JavascriptInterface public void ping() {
            ZvService s = ZvService.instance;
            if (s != null) s.ping();
        }

        @JavascriptInterface public void callAudio(boolean speaker, boolean video) {
            ZvService s = ZvService.instance;
            if (s != null) s.callAudio(speaker, video);
        }

        @JavascriptInterface public void share(String text, String url) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text == null || text.isEmpty() ? url : text + "\n" + url);
                try {
                    startActivity(Intent.createChooser(i, "Отправить ссылку"));
                } catch (Exception e) {}
            });
        }
    }
}
