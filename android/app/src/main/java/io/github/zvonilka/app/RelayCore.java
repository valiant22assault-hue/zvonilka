package io.github.zvonilka.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Фоновая связь с реле, пока звонилка закрыта: слушает топики всех контактов,
 * замечает входящие звонки, их отмену и новые сообщения, умеет отклонить звонок.
 * Протокол — тот же, что в index.html. Без Android, чтобы проверять на компьютере.
 * Вся работа идёт в одном потоке exec.
 */
final class RelayCore implements MqttLink.Listener {
    interface Events {
        void ring(Contact c, String callId);
        void ringStopped(Contact c, String callId, boolean missed);
        void message(Contact c, String text);
        void relays(boolean online);
        void log(String msg);
    }

    /** Помнит, о каких сообщениях уже сообщили (переживает перезапуск). */
    interface Seen {
        boolean firstTime(String key);
    }

    static final class Contact {
        final String id, name;
        final Pair pair;

        Contact(String id, String name, Pair pair) {
            this.id = id;
            this.name = name;
            this.pair = pair;
        }
    }

    static final long RING_MS = 60_000;   // столько звонок ждёт ответа — как в index.html
    static final List<String> DEFAULT_RELAYS = Arrays.asList(
            "wss://broker.emqx.io:8084/mqtt", "wss://broker.hivemq.com:8884/mqtt");

    final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient http;
    private final Events ev;
    private final Seen seen;
    private final List<MqttLink> links = new ArrayList<>();
    private final Map<String, Contact> byTopic = new HashMap<>();
    private final LinkedHashSet<String> handled = new LinkedHashSet<>();
    private List<String> relayUrls = new ArrayList<>();
    private String state = "", meId = "", presenceTopic;
    private Contact ringC;
    private String ringId;
    private ScheduledFuture<?> ringTimeout;
    private boolean busy;

    RelayCore(OkHttpClient http, Events ev, Seen seen) {
        this.http = http;
        this.ev = ev;
        this.seen = seen;
        exec.scheduleWithFixedDelay(this::announce, 60, 60, TimeUnit.SECONDS);
    }

    // ---------- команды (из любого потока) ----------

    /** Новые данные от страницы: кто я, контакты с секретами, адреса реле. */
    void configure(String json) {
        exec.execute(() -> apply(json));
    }

    /** Страница сама ответила на звонок или отклонила его — перестаём звонить. */
    void ringDone(String callId) {
        exec.execute(() -> { if (callId != null && callId.equals(ringId)) stopRing(false); });
    }

    void decline(String callId) {
        exec.execute(() -> {
            Contact c = ringC;
            if (c == null || callId == null || !callId.equals(ringId)) return;
            stopRing(false);
            signal(c, "decline", callId);
            publishAll(c.pair.topic + "/call", new byte[0], true);   // убираем приглашение с реле
        });
    }

    private void signal(Contact c, String t, String callId) {
        try {
            JSONObject o = new JSONObject().put("t", t).put("callId", callId).put("from", meId);
            publishAll(c.pair.topic + "/sig", c.pair.seal(o.toString()), false);
        } catch (Exception e) {
            ev.log(t + ": " + e);
        }
    }

    /** Идёт разговор: новые звонки пусть встречает страница («занято»). */
    void setBusy(boolean b) {
        exec.execute(() -> busy = b);
    }

    void kick() {
        exec.execute(() -> { for (MqttLink l : links) l.kick(); });
    }

    void reconnect() {
        exec.execute(() -> { for (MqttLink l : links) l.reconnect(); });
    }

    void shutdown() {
        exec.execute(() -> {
            closeLinks();
            exec.shutdown();
        });
    }

    // ---------- состояние ----------

    private void apply(String json) {
        if (json == null || json.equals(state)) return;
        state = json;
        JSONObject st;
        try {
            st = new JSONObject(json);
        } catch (JSONException e) {
            ev.log("bad state: " + e);
            return;
        }
        JSONObject me = st.optJSONObject("me");
        String id = me != null ? me.optString("id", "") : "";
        List<String> urls = new ArrayList<>();
        JSONArray rs = st.optJSONArray("relays");
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            String u = rs.optString(i, "");
            if (u.startsWith("wss://") || u.startsWith("ws://")) urls.add(u);
        }
        if (urls.isEmpty()) urls = DEFAULT_RELAYS;
        byTopic.clear();
        JSONArray cs = st.optJSONArray("contacts");
        if (cs != null) for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o == null) continue;
            try {
                Contact c = new Contact(o.getString("id"), o.optString("name", "Контакт"), new Pair(o.getString("secret")));
                byTopic.put(c.pair.topic, c);
            } catch (Exception e) {
                ev.log("bad contact: " + e);
            }
        }
        boolean restart = !id.equals(meId) || !urls.equals(relayUrls);
        meId = id;
        relayUrls = urls;
        presenceTopic = id.isEmpty() ? null : Pair.presenceTopic(id);
        if (id.isEmpty() || byTopic.isEmpty()) {   // некого слушать
            closeLinks();
            ev.relays(false);
            return;
        }
        if (restart || links.isEmpty()) {
            closeLinks();
            for (String u : urls) links.add(new MqttLink(u, http, exec, this));
        }
        for (Contact c : byTopic.values()) for (MqttLink l : links) l.subscribe(c.pair.topic + "/#");
    }

    private void closeLinks() {
        for (MqttLink l : links) l.close();
        links.clear();
    }

    private boolean online() {
        for (MqttLink l : links) if (l.ready()) return true;
        return false;
    }

    private void publishAll(String topic, byte[] payload, boolean retain) {
        for (MqttLink l : links) l.publish(topic, payload, retain);
    }

    // «В сети»: та же отметка, что ставит страница, — звонящий видит, что до нас можно дозвониться.
    private void announce() {
        if (presenceTopic == null) return;
        for (MqttLink l : links) if (l.ready()) l.publish(presenceTopic, Pair.utf8("1:" + System.currentTimeMillis()), true);
    }

    // ---------- события реле ----------

    @Override public byte[][] will() {
        return presenceTopic == null ? null
                : new byte[][]{Pair.utf8(presenceTopic), Pair.utf8("0:" + System.currentTimeMillis())};
    }

    @Override public void onReady(MqttLink l) {
        if (presenceTopic != null) l.publish(presenceTopic, Pair.utf8("1:" + System.currentTimeMillis()), true);
        ev.relays(true);
    }

    @Override public void onDown(MqttLink l) {
        ev.relays(online());
    }

    @Override public void onMessage(MqttLink l, String topic, byte[] payload, boolean retained) {
        int cut = nthSlash(topic, 3);   // zvonilka/v1/<пара>/<что>
        if (cut < 0) return;
        Contact c = byTopic.get(topic.substring(0, cut));
        if (c == null) return;
        String sub = topic.substring(cut + 1);
        if (payload.length == 0) {
            // Приглашение убрали с реле, а мы ещё звоним — значит, звонящий передумал.
            if (sub.equals("call") && c == ringC) stopRing(true);
            return;
        }
        JSONObject d;
        try {
            d = new JSONObject(c.pair.open(payload));
        } catch (Exception e) {
            return;
        }
        if (!c.id.equals(d.optString("from"))) return;
        String t = d.optString("t");
        if (t.equals("offer") && sub.equals("call")) onOffer(c, d);
        else if (t.equals("cancel")) {
            if (c == ringC && d.optString("callId").equals(ringId)) stopRing(true);
        } else if (t.equals("msg") && sub.startsWith("m/")) {
            String id = d.optString("id"), text = d.optString("text");
            if (!id.isEmpty() && !text.isEmpty() && seen.firstTime(c.id + "/" + id)) ev.message(c, text);
        }
    }

    private void onOffer(Contact c, JSONObject d) {
        String callId = d.optString("callId");
        long age = Math.max(0, System.currentTimeMillis() - d.optLong("ts"));
        if (callId.isEmpty() || !handled.add(callId) || age > RING_MS) return;
        trim(handled, 200);
        if (busy || ringC != null) return;
        ringC = c;
        ringId = callId;
        ringTimeout = exec.schedule(() -> stopRing(true), Math.max(1000, RING_MS - age), TimeUnit.MILLISECONDS);
        ev.ring(c, callId);
        signal(c, "ringing", callId);   // звонящий увидит «телефон звонит»
    }

    private void stopRing(boolean missed) {
        if (ringC == null) return;
        Contact c = ringC;
        String id = ringId;
        ringC = null;
        ringId = null;
        if (ringTimeout != null) ringTimeout.cancel(false);
        ev.ringStopped(c, id, missed);
    }

    private static int nthSlash(String s, int n) {
        int i = -1;
        while (n-- > 0) if ((i = s.indexOf('/', i + 1)) < 0) return -1;
        return i;
    }

    private static void trim(LinkedHashSet<String> set, int max) {
        Iterator<String> it = set.iterator();
        while (set.size() > max && it.hasNext()) { it.next(); it.remove(); }
    }
}
