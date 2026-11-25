package io.github.zvonilka.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * (поэтому ссылки-приглашения ведут туда же), но, если сайт недоступен, берётся последняя скачанная копия,
 * а если её нет — встроенная в приложение.
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
        fastStart = ACTION_ACCEPT.equals(i.getAction());   // отвечаем на звонок — не ждём сайт, берём свою копию
        handleAction(i);
        String hash = hashFrom(i);
        web.loadUrl(HOME + (hash != null ? hash : ""));
        ZvService.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        checkSetup();
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
            acceptAt = System.currentTimeMillis();
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

    // zvonilka://add/<приглашение> — кнопка «Открыть в приложении» на сайте; https-ссылки на сайт — сразу (см. AndroidManifest).
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

    // ---------- что нужно, чтобы звонки доходили ----------
    // При каждом открытии проверяем разрешения и режим батареи. Чего-то не хватает — по одному пункту объясняем,
    // зачем это нужно и что нажать, и открываем нужную настройку. «Позже» — не напоминаем полдня; во время звонка молчим.

    private static final long SETUP_LATER = 12 * 3600_000L;
    private AlertDialog setupDialog;
    private boolean setupOpened;    // настройку открыли мы — когда вернутся, сразу проверим следующий пункт
    private boolean batteryAsked;   // системный вопрос про батарею уже был — дальше ведём в настройки приложения
    private long acceptAt;

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    // Один пункт: что не так, зачем это нужно, что нажать — и кнопка, которая это открывает.
    private static final class Step {
        final String title, text, button;
        final Runnable fix;
        Step(String title, String text, String button, Runnable fix) {
            this.title = title;
            this.text = text;
            this.button = button;
            this.fix = fix;
        }
    }

    private void checkSetup() {
        if (setupDialog != null && setupDialog.isShowing()) return;
        boolean going = setupOpened;
        setupOpened = false;
        if (callBusy()) return;
        if (!going && System.currentTimeMillis() - prefs.getLong("setupLater", 0) < SETUP_LATER) return;
        List<Step> steps = missingSteps();
        if (steps.isEmpty()) {
            if (going) Toast.makeText(this, "Готово: всё нужное для звонков разрешено", Toast.LENGTH_LONG).show();
            return;
        }
        Step s = steps.get(0);
        int more = steps.size() - 1;
        setupDialog = new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setMessage(s.text + (more > 0 ? "\n\nПосле этого — ещё " + more + (more == 1 ? " шаг." : " шага.") : ""))
                .setPositiveButton(s.button, (d, w) -> s.fix.run())
                .setNegativeButton("Позже", (d, w) -> setupLater())
                .setOnCancelListener(d -> setupLater())
                .show();
    }

    private void setupLater() {
        prefs.edit().putLong("setupLater", System.currentTimeMillis()).apply();
    }

    // Отвечают на звонок или разговаривают — не до настроек.
    private boolean callBusy() {
        ZvService s = ZvService.instance;
        return pendingPerm != null || ZvService.ringingCallId != null || (s != null && s.inCall())
                || System.currentTimeMillis() - acceptAt < 60_000;
    }

    private List<Step> missingSteps() {
        List<Step> steps = new ArrayList<>();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel calls = nm.getNotificationChannel("calls");
        boolean notifPerm = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
        if (!notifPerm || !nm.areNotificationsEnabled() || (calls != null && calls.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            String why = "Входящий звонок приходит уведомлением. Без уведомлений вы не узнаете о звонке, когда звонилка закрыта.";
            if (!notifPerm && canAsk(Manifest.permission.POST_NOTIFICATIONS)) {
                steps.add(new Step("Уведомления о звонках", why + "\n\nСейчас телефон спросит разрешение — нажмите «Разрешить».",
                        "Продолжить", () -> ask(Manifest.permission.POST_NOTIFICATIONS)));
            } else {
                steps.add(new Step("Уведомления о звонках", why + "\n\nОткроются настройки уведомлений звонилки — включите их, в том числе «Входящие звонки».",
                        "Открыть настройки", () -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
            }
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            String why = "Без микрофона собеседник вас не услышит.";
            if (canAsk(Manifest.permission.RECORD_AUDIO)) {
                steps.add(new Step("Микрофон", why + "\n\nСейчас телефон спросит разрешение — нажмите «Разрешить» (или «При использовании приложения»).",
                        "Продолжить", () -> ask(Manifest.permission.RECORD_AUDIO)));
            } else {
                steps.add(new Step("Микрофон", why + "\n\nОткроются настройки звонилки — выберите «Разрешения», затем «Микрофон» и разрешите его.",
                        "Открыть настройки", () -> open(appDetails())));
            }
        }
        PowerManager pm = getSystemService(PowerManager.class);
        boolean restricted = Build.VERSION.SDK_INT >= 28 && getSystemService(ActivityManager.class).isBackgroundRestricted();
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) || restricted) {
            String why = "Телефон экономит батарею и ограничивает приложения, которые сейчас не открыты. Из-за этого при погашенном экране "
                    + "звонки приходят с опозданием или не приходят совсем. В режиме «Без ограничений» звонилка всегда на связи.";
            if (!restricted && !batteryAsked) {
                steps.add(new Step("Работа в фоне", why + "\n\nСейчас телефон спросит, можно ли звонилке работать в фоне, — нажмите «Разрешить».",
                        "Продолжить", () -> {
                            batteryAsked = true;
                            open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                        }));
            } else {
                steps.add(new Step("Работа в фоне", why + "\n\nОткроются настройки звонилки — найдите «Батарея» (или «Расход батареи») и выберите «Без ограничений».",
                        "Открыть настройки", () -> open(appDetails())));
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            steps.add(new Step("Звонок на весь экран", "Так входящий звонок появляется на весь экран, даже на заблокированном телефоне, — как обычный звонок."
                    + "\n\nОткроется настройка — включите переключатель.",
                    "Открыть настройки", () -> open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())))));
        }
        return steps;
    }

    // Системный вопрос о разрешении можно задать, пока его не запретили насовсем; дальше — только через настройки.
    private boolean canAsk(String perm) {
        return !prefs.getBoolean("asked:" + perm, false) || shouldShowRequestPermissionRationale(perm);
    }

    private void ask(String perm) {
        prefs.edit().putBoolean("asked:" + perm, true).apply();
        setupOpened = true;
        requestPermissions(new String[]{perm}, REQ_START);
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
    }

    private void open(Intent i) {
        setupOpened = true;
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(appDetails());   // у некоторых телефонов нет отдельного экрана — открываем настройки приложения
            } catch (Exception e2) {
                setupOpened = false;
                Toast.makeText(this, "Не получилось открыть настройки. Откройте их сами: Настройки → Приложения → Звонилка.", Toast.LENGTH_LONG).show();
            }
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
            ZvService.start(this);   // с разрешением на уведомления служба покажет своё; следующий пункт — в onResume
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
            if (body != null && name.equals("index.html")) {
                if (new String(body, StandardCharsets.UTF_8).contains("window.ZvonilkaApp")) savePage(body);
                else body = null;
            }
            if (body == null && name.equals("index.html")) body = savedPage();
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

    private void savePage(byte[] body) {
        File tmp = new File(getFilesDir(), "page.html.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(new File(getFilesDir(), "page.html"));
    }

    // Страница, скачанная до установки новой версии приложения, может быть старше встроенной в неё.
    private byte[] savedPage() {
        File f = new File(getFilesDir(), "page.html");
        try {
            if (f.lastModified() <= getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime) return null;
            try (InputStream in = new FileInputStream(f)) {
                return readAll(in);
            }
        } catch (IOException | PackageManager.NameNotFoundException e) {
            return null;
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
