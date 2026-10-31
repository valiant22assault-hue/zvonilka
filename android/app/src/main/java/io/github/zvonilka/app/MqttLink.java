package io.github.zvonilka.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Минимальный клиент MQTT 3.1.1 поверх WebSocket — то же, что MqttLink в index.html:
 * подключиться (с «завещанием» о выходе из сети), подписаться, опубликовать, переподключаться.
 * Все методы вызываются только в потоке exec; ответы OkHttp туда же и пересылаются.
 */
final class MqttLink {
    interface Listener {
        /** {топик, сообщение}, которое брокер опубликует сам, если связь оборвётся, или null. */
        byte[][] will();
        void onReady(MqttLink link);
        void onDown(MqttLink link);
        void onMessage(MqttLink link, String topic, byte[] payload, boolean retained);
    }

    static final int KEEPALIVE = 120;          // секунд; пингуем чаще, чтобы брокер не счёл нас пропавшими
    private static final long PING_EVERY = 60;
    private static final SecureRandom RNG = new SecureRandom();

    final String url;
    private final OkHttpClient http;
    private final ScheduledExecutorService exec;
    private final Listener listener;
    private final Set<String> subs = new LinkedHashSet<>();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private WebSocket ws;
    private boolean ready, closed;
    private byte[] buf = new byte[0];
    private long backoff = 2000, lastRx;
    private int pid;
    private ScheduledFuture<?> ping, retry;

    MqttLink(String url, OkHttpClient http, ScheduledExecutorService exec, Listener listener) {
        this.url = url;
        this.http = http;
        this.exec = exec;
        this.listener = listener;
        connect();
    }

    boolean ready() {
        return ready;
    }

    private void connect() {
        if (closed) return;
        cancel(retry);
        retry = null;
        if (ws != null) ws.cancel();
        buf = new byte[0];
        Request req = new Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                exec.execute(() -> { if (s == ws) s.send(ByteString.of(connectPacket())); });
            }
            @Override public void onMessage(WebSocket s, ByteString bytes) {
                byte[] b = bytes.toByteArray();
                exec.execute(() -> { if (s == ws) feed(b); });
            }
            @Override public void onClosing(WebSocket s, int code, String reason) {
                s.close(1000, null);
            }
            @Override public void onClosed(WebSocket s, int code, String reason) {
                exec.execute(() -> down(s));
            }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                exec.execute(() -> down(s));
            }
        });
    }

    private void down(WebSocket s) {
        if (s != ws || closed) return;
        ws = null;
        ready = false;
        cancel(ping);
        listener.onDown(this);
        retry = exec.schedule(this::connect, backoff, TimeUnit.MILLISECONDS);
        backoff = Math.min(60000, backoff * 2);
    }

    /** Сеть появилась: если связи нет — подключаемся сразу, не дожидаясь паузы. */
    void kick() {
        if (ready || closed) return;
        backoff = 2000;
        connect();
    }

    /** Сеть сменилась (Wi-Fi ↔ мобильная): старое соединение, скорее всего, мертво. */
    void reconnect() {
        if (closed) return;
        ready = false;
        cancel(ping);
        backoff = 2000;
        connect();
    }

    void close() {
        closed = true;
        ready = false;
        cancel(ping);
        cancel(retry);
        if (ws != null) ws.close(1000, null);
        ws = null;
    }

    void subscribe(String topic) {
        if (subs.add(topic) && ready) sendSubscribe(topic);
    }

    void publish(String topic, byte[] payload, boolean retain) {
        send(packet(0x30 | (retain ? 1 : 0), concat(str(topic.getBytes(StandardCharsets.UTF_8)), payload)));
    }

    private void send(byte[] p) {
        if (ready && ws != null) ws.send(ByteString.of(p));
        else if (queue.size() < 200) queue.add(p);   // отправим, когда связь вернётся
    }

    private void sendSubscribe(String topic) {
        pid = pid % 65535 + 1;
        byte[] id = {(byte) (pid >> 8), (byte) pid};
        ws.send(ByteString.of(packet(0x82, concat(id, str(topic.getBytes(StandardCharsets.UTF_8)), new byte[]{0}))));
    }

    private void pingTick() {
        if (!ready || ws == null) return;
        // Ответов давно нет — соединение повисло (например, телефон сменил сеть), начинаем заново.
        if (System.currentTimeMillis() - lastRx > (PING_EVERY * 2 + 30) * 1000) { reconnect(); return; }
        ws.send(ByteString.of(new byte[]{(byte) 0xc0, 0}));
    }

    private byte[] connectPacket() {
        byte[][] will = listener.will();
        byte[] rnd = new byte[8];
        RNG.nextBytes(rnd);
        StringBuilder id = new StringBuilder("zva");
        for (byte b : rnd) id.append(String.format("%02x", b & 255));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        put(b, str("MQTT".getBytes(StandardCharsets.UTF_8)));
        b.write(4);
        b.write(0x02 | (will != null ? 0x24 : 0));   // чистая сессия; завещание с retain
        b.write(KEEPALIVE >> 8);
        b.write(KEEPALIVE & 255);
        put(b, str(id.toString().getBytes(StandardCharsets.UTF_8)));
        if (will != null) {
            put(b, str(will[0]));
            put(b, str(will[1]));
        }
        return packet(0x10, b.toByteArray());
    }

    private void feed(byte[] chunk) {
        lastRx = System.currentTimeMillis();
        byte[] b = buf.length == 0 ? chunk : concat(buf, chunk);
        int pos = 0;
        while (b.length - pos >= 2) {
            int len = 0, mul = 1, i = pos + 1, x;
            do {
                if (i >= b.length) { buf = Arrays.copyOfRange(b, pos, b.length); return; }
                x = b[i++] & 255;
                len += (x & 127) * mul;
                mul *= 128;
            } while ((x & 128) != 0);
            if (b.length < i + len) break;
            handle(b[pos] & 255, Arrays.copyOfRange(b, i, i + len));
            pos = i + len;
        }
        buf = Arrays.copyOfRange(b, pos, b.length);
    }

    private void handle(int type, byte[] body) {
        int kind = type >> 4;
        if (kind == 2) {                       // CONNACK
            if (body.length < 2 || body[1] != 0) { ws.close(1000, null); return; }
            ready = true;
            backoff = 2000;
            for (String t : subs) sendSubscribe(t);
            while (!queue.isEmpty()) ws.send(ByteString.of(queue.poll()));
            cancel(ping);
            ping = exec.scheduleWithFixedDelay(this::pingTick, PING_EVERY, PING_EVERY, TimeUnit.SECONDS);
            listener.onReady(this);
        } else if (kind == 3 && body.length >= 2) {   // PUBLISH
            int tl = ((body[0] & 255) << 8) | (body[1] & 255);
            int qos = (type >> 1) & 3;
            if (body.length < 2 + tl + (qos > 0 ? 2 : 0)) return;
            String topic = new String(body, 2, tl, StandardCharsets.UTF_8);
            byte[] payload = Arrays.copyOfRange(body, 2 + tl + (qos > 0 ? 2 : 0), body.length);
            if (qos == 1) send(packet(0x40, Arrays.copyOfRange(body, 2 + tl, 4 + tl)));
            listener.onMessage(this, topic, payload, (type & 1) == 1);
        }
    }

    private static byte[] str(byte[] s) {
        return concat(new byte[]{(byte) (s.length >> 8), (byte) s.length}, s);
    }

    private static byte[] packet(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        int n = body.length;
        do {
            int d = n % 128;
            n /= 128;
            if (n > 0) d |= 128;
            out.write(d);
        } while (n > 0);
        put(out, body);
        return out.toByteArray();
    }

    // ByteArrayOutputStream.writeBytes есть только с Android 13.
    private static void put(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }
}
