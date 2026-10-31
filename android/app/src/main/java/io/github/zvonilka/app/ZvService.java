package io.github.zvonilka.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Служба «на связи»: работает всегда, пока телефон включён, держит связь с реле (RelayCore)
 * и превращает входящий звонок в настоящий звонок телефона — экран поверх блокировки, мелодия, вибрация.
 * Во время разговора помечена как служба микрофона, чтобы Android не глушил звук в фоне.
 */
public class ZvService extends Service implements RelayCore.Events {
    static final String ACTION_DECLINE = "zv.DECLINE", ACTION_HANGUP = "zv.HANGUP", EXTRA_CALL = "callId";
    // Звук сообщений играем сами (его можно выбрать), поэтому канал «chat» беззвучный; старый «messages» был со звуком.
    private static final String CH_LINK = "link", CH_INCALL = "incall", CH_CALL = "calls", CH_MSG = "chat", CH_MISSED = "missed";
    private static final int ID_FG = 1, ID_RING = 2, ID_MSG = 3, ID_MISSED = 4, ID_INCALL = 5;
    private static final String TAG = "zv";

    static volatile ZvService instance;
    static volatile String ringingCallId;
    private static final Object CALL_LOG = new Object();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> msgTags = new HashSet<>();
    private RelayCore core;
    private NotificationManager nm;
    private SharedPreferences prefs;
    private LinkedHashSet<String> seen;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private PowerManager.WakeLock ringLock, callLock, msgLock, proxLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback netCb;
    private boolean inCall, online;
    private String callName = "";
    private long callSince;

    static void start(Context ctx) {
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, ZvService.class));
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
        }
    }

    static boolean isRinging(String callId) {
        return callId != null && callId.equals(ringingCallId);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("zv", MODE_PRIVATE);
        nm = getSystemService(NotificationManager.class);
        channels();
        foreground();
        PowerManager pm = getSystemService(PowerManager.class);
        ringLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zvonilka:ring");
        callLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zvonilka:call");
        msgLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zvonilka:msg");
        msgLock.setReferenceCounted(false);
        // Телефон у уха — экран гаснет, чтобы щека не нажимала кнопки.
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zvonilka:proximity");
        }
        wifiLock = getSystemService(WifiManager.class).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zvonilka:call");
        OkHttpClient http = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();
        core = new RelayCore(http, this, this::firstTime);
        core.configure(prefs.getString("state", null));
        // Сеть вернулась или сменилась — переподключаемся сразу, а не по таймеру.
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            private Network last;
            @Override public void onAvailable(Network n) {
                if (last != null && !n.equals(last)) core.reconnect();
                else core.kick();
                last = n;
            }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception e) { netCb = null; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_DECLINE.equals(a)) decline(intent.getStringExtra(EXTRA_CALL));
        else if (ACTION_HANGUP.equals(a)) MainActivity.runJs("window.zvApp && zvApp.hangup()");
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        nm.cancel(ID_INCALL);
        core.shutdown();
        stopRingtone();
        release(ringLock);
        release(callLock);
        if (wifiLock.isHeld()) wifiLock.release();
        if (netCb != null) try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception e) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- команды от страницы ----------

    void stateChanged() {
        core.configure(prefs.getString("state", null));
    }

    void ringDone(String callId) {
        core.ringDone(callId);
    }

    void decline(String callId) {
        if (callId == null) return;
        core.decline(callId);
        MainActivity.runJs("window.zvApp && zvApp.decline(" + JSONObject.quote(callId) + ")");
    }

    void setInCall(boolean on, String name) {
        main.post(() -> {
            if (on == inCall && (name == null || name.equals(callName))) return;
            if (on && !inCall) callSince = System.currentTimeMillis();
            inCall = on;
            callName = name == null ? "" : name;
            core.setBusy(on);
            if (on) {
                if (!callLock.isHeld()) callLock.acquire(6 * 3600_000L);
                if (!wifiLock.isHeld()) wifiLock.acquire();
            } else {
                release(callLock);
                if (wifiLock.isHeld()) wifiLock.release();
                releaseProximity();
                AudioManager am = getSystemService(AudioManager.class);
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else am.setSpeakerphoneOn(false);
            }
            foreground();
            // «Идёт звонок» — отдельное уведомление: его можно просто убрать, даже если строка «на связи» скрыта.
            if (on) nm.notify(ID_INCALL, callNotification());
            else nm.cancel(ID_INCALL);
        });
    }

    /** Громкая связь или динамик у уха; у уха (и без видео) экран гаснет от датчика приближения. */
    void callAudio(boolean speaker, boolean video) {
        main.post(() -> {
            if (!inCall) return;
            AudioManager am = getSystemService(AudioManager.class);
            if (Build.VERSION.SDK_INT >= 31) {
                if (!speaker && headset(am)) am.clearCommunicationDevice();   // наушники — звук идёт в них
                else for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int want = speaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                    if (d.getType() == want) { am.setCommunicationDevice(d); break; }
                }
            } else {
                am.setSpeakerphoneOn(speaker);
            }
            if (!speaker && !video && proxLock != null) {
                if (!proxLock.isHeld()) proxLock.acquire(6 * 3600_000L);
            } else {
                releaseProximity();
            }
        });
    }

    private static boolean headset(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return true;
            }
        }
        return false;
    }

    private void releaseProximity() {
        if (proxLock != null && proxLock.isHeld()) proxLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
    }

    /** Звонилка на экране, но пришло сообщение не в открытую переписку — короткий звук, как в мессенджерах. */
    void ping() {
        main.post(() -> { if (MainActivity.visible) messageSound(); });
    }

    void clearMessages() {
        main.post(() -> {
            for (String tag : msgTags) nm.cancel(tag, ID_MSG);
            msgTags.clear();
        });
    }

    // ---------- уведомление службы ----------

    // Уведомление службы — всегда «на связи»: если его скрыли, Android не показывает и его замену,
    // поэтому «Идёт звонок» живёт отдельно (ID_INCALL). Во время звонка служба помечена ещё и как микрофонная.
    private void foreground() {
        Notification n = linkNotification();
        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (inCall && mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            ServiceCompat.startForeground(this, ID_FG, n, type);
        } catch (Exception e) {
            // Микрофон в фоне не дали (приложение было свёрнуто) — хотя бы остаёмся на связи.
            Log.w(TAG, "startForeground: " + e);
            try {
                ServiceCompat.startForeground(this, ID_FG, n, Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
            } catch (Exception e2) {
                Log.w(TAG, "startForeground: " + e2);
            }
        }
    }

    // Без такого уведомления Android закроет службу. Его можно скрыть, выключив канал «Связь» в настройках:
    // служба продолжит работать.
    private Notification linkNotification() {
        return new NotificationCompat.Builder(this, CH_LINK)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(online ? "Звонилка на связи" : "Звонилка: нет связи с сервером")
                .setContentText(online ? null : "Подключимся, как только появится интернет")
                .setContentIntent(openApp(null))
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private Notification callNotification() {
        PendingIntent hang = PendingIntent.getService(this, 10,
                new Intent(this, ZvService.class).setAction(ACTION_HANGUP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH_INCALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("Идёт звонок")
                .setContentText(callName.isEmpty() ? "Нажмите, чтобы вернуться" : callName)
                .setContentIntent(openApp(null))
                .addAction(0, "Завершить", hang)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(callSince)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build();
    }

    private PendingIntent openApp(String contactId) {
        Intent i = contactId == null ? new Intent(this, MainActivity.class) : MainActivity.openIntent(this, contactId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int code = contactId == null ? 0 : 100 + (contactId.hashCode() & 0xffff);
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void channels() {
        NotificationChannel link = new NotificationChannel(CH_LINK, "Связь", NotificationManager.IMPORTANCE_MIN);
        link.setDescription("Постоянное уведомление: звонилка ждёт звонков в фоне");
        link.setShowBadge(false);
        NotificationChannel incall = new NotificationChannel(CH_INCALL, "Идущий звонок", NotificationManager.IMPORTANCE_LOW);
        incall.setShowBadge(false);
        NotificationChannel calls = new NotificationChannel(CH_CALL, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(null, null);          // мелодию играем сами, по кругу
        calls.enableVibration(false);        // и вибрируем тоже сами
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel msgs = new NotificationChannel(CH_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
        msgs.setSound(null, null);           // звук — выбранный в звонилке, играем сами
        msgs.enableVibration(false);
        NotificationChannel missed = new NotificationChannel(CH_MISSED, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT);
        for (NotificationChannel c : new NotificationChannel[]{link, incall, calls, msgs, missed}) nm.createNotificationChannel(c);
        nm.deleteNotificationChannel("messages");   // канал первой версии
    }

    // ---------- события реле ----------

    @Override public void ring(RelayCore.Contact c, String callId) {
        main.post(() -> {
            ringingCallId = callId;
            if (!ringLock.isHeld()) ringLock.acquire(RelayCore.RING_MS + 5000);
            int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent answer = PendingIntent.getActivity(this, 1, MainActivity.acceptIntent(this, callId), flags);
            PendingIntent decline = PendingIntent.getService(this, 2,
                    new Intent(this, ZvService.class).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL, callId), flags);
            PendingIntent full = PendingIntent.getActivity(this, 3, IncomingCallActivity.intent(this, c.name, callId), flags);
            Person caller = new Person.Builder().setName(c.name).setImportant(true).build();
            Notification n = new NotificationCompat.Builder(this, CH_CALL)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(c.name)
                    .setContentText("Звонит вам")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setFullScreenIntent(full, true)
                    .setContentIntent(full)
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
                    .setTimeoutAfter(RelayCore.RING_MS)
                    .build();
            nm.notify(ID_RING, n);
            startRingtone();
        });
    }

    @Override public void ringStopped(RelayCore.Contact c, String callId, String reason) {
        main.post(() -> {
            if (callId != null && callId.equals(ringingCallId)) ringingCallId = null;
            nm.cancel(ID_RING);
            stopRingtone();
            release(ringLock);
            IncomingCallActivity.close();
            if (RelayCore.DECLINED.equals(reason)) logCall(c, callId, "rejected");
            if (!RelayCore.MISSED.equals(reason)) return;
            logCall(c, callId, "missed");
            nm.notify("missed:" + c.id, ID_MISSED, new NotificationCompat.Builder(this, CH_MISSED)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle("Пропущенный звонок")
                    .setContentText(c.name)
                    .setContentIntent(openApp(c.id))
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setAutoCancel(true)
                    .build());
        });
    }

    // Пропущенные и отклонённые здесь звонки страница заберёт в историю переписки, когда откроется
    // (если открыта — сразу). Повторы она отбросит сама: у записи тот же id звонка.
    private void logCall(RelayCore.Contact c, String callId, String kind) {
        if (callId == null) return;
        synchronized (CALL_LOG) {
            try {
                JSONArray a = new JSONArray(prefs.getString("callLog", "[]"));
                a.put(new JSONObject().put("contact", c.id).put("callId", callId).put("kind", kind).put("ts", System.currentTimeMillis()));
                while (a.length() > 100) a.remove(0);
                prefs.edit().putString("callLog", a.toString()).apply();
            } catch (Exception e) {
                Log.w(TAG, "callLog: " + e);
            }
        }
        MainActivity.runJs("window.zvApp && zvApp.callLogChanged && zvApp.callLogChanged()");
    }

    /** Забрать накопленные записи о звонках (для страницы). */
    static String takeCallLog(Context ctx) {
        synchronized (CALL_LOG) {
            SharedPreferences p = ctx.getSharedPreferences("zv", MODE_PRIVATE);
            String s = p.getString("callLog", "[]");
            p.edit().remove("callLog").apply();
            return s;
        }
    }

    @Override public void message(RelayCore.Contact c, String text) {
        msgLock.acquire(5000);
        main.post(() -> {
            if (MainActivity.visible) return;   // звонилка на экране — она сама покажет
            String tag = "msg:" + c.id;
            msgTags.add(tag);
            nm.notify(tag, ID_MSG, new NotificationCompat.Builder(this, CH_MSG)
                    .setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(c.name)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(c.id))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .build());
            messageSound();
        });
    }

    private void messageSound() {
        int mode = getSystemService(AudioManager.class).getRingerMode();
        if (mode == AudioManager.RINGER_MODE_SILENT) return;
        // Во время разговора звук уведомления мешал бы, только короткая вибрация.
        if (mode == AudioManager.RINGER_MODE_NORMAL && !inCall) {
            Sounds.play(this, Sounds.get(this, Sounds.MSG), AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT);
        }
        Vibrator v = getSystemService(Vibrator.class);
        if (Sounds.vibrate(this) && v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 90, 120}, -1),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        }
    }

    @Override public void relays(boolean on) {
        main.post(() -> {
            if (on == online) return;
            online = on;
            foreground();
        });
    }

    @Override public void log(String msg) {
        Log.i(TAG, msg);
    }

    // О каких сообщениях уже сообщили: конверты лежат на реле, пока их не заберёт страница,
    // и после переподключения приходят снова.
    private boolean firstTime(String key) {
        synchronized (this) {
            if (seen == null) {
                seen = new LinkedHashSet<>();
                try {
                    JSONArray a = new JSONArray(prefs.getString("seen", "[]"));
                    for (int i = 0; i < a.length(); i++) seen.add(a.getString(i));
                } catch (Exception e) {}
            }
            if (!seen.add(key)) return false;
            Iterator<String> it = seen.iterator();
            while (seen.size() > 300 && it.hasNext()) { it.next(); it.remove(); }
            prefs.edit().putString("seen", new JSONArray(seen).toString()).apply();
            return true;
        }
    }

    // ---------- мелодия и вибрация ----------

    private void startRingtone() {
        stopRingtone();
        AudioManager am = getSystemService(AudioManager.class);
        int mode = am.getRingerMode();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = Sounds.play(this, Sounds.get(this, Sounds.CALL), AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                else main.postDelayed(replay, 1000);
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && Sounds.vibrate(this)) {
            vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 900, 700}, 0), attrs);
            }
        }
    }

    // До Android 9 мелодия не умеет играть по кругу сама — запускаем заново, когда закончится.
    private final Runnable replay = new Runnable() {
        @Override public void run() {
            if (ringtone == null) return;
            if (!ringtone.isPlaying()) ringtone.play();
            main.postDelayed(this, 1000);
        }
    };

    private void stopRingtone() {
        main.removeCallbacks(replay);
        if (ringtone != null) ringtone.stop();
        ringtone = null;
        if (vibrator != null) vibrator.cancel();
        vibrator = null;
    }

    private static void release(PowerManager.WakeLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }
}
