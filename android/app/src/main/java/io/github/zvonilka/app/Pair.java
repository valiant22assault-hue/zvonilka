package io.github.zvonilka.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ключи пары собеседников — так же, как в index.html (pairOf, seal, unseal):
 * топик на реле и ключ AES-GCM выводятся из общего секрета, конверт = 12 байт IV + шифротекст с тегом.
 */
final class Pair {
    private static final SecureRandom RNG = new SecureRandom();

    final String topic;
    private final SecretKeySpec key;

    Pair(String secret) {
        byte[] raw = b64uDecode(secret);
        topic = "zvonilka/v1/" + b64u(Arrays.copyOf(sha256(raw, utf8("topic")), 18));
        key = new SecretKeySpec(sha256(raw, utf8("key")), "AES");
    }

    /** Топик «в сети / не в сети» для собственного id. */
    static String presenceTopic(String id) {
        return "zvonilka/v1/p/" + b64u(Arrays.copyOf(sha256(utf8("presence"), utf8(id)), 18));
    }

    String open(byte[] box) throws GeneralSecurityException {
        if (box.length < 12 + 16) throw new GeneralSecurityException("short envelope");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, box, 0, 12));
        return new String(c.doFinal(box, 12, box.length - 12), StandardCharsets.UTF_8);
    }

    byte[] seal(String json) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] body = c.doFinal(utf8(json));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return out;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] b64uDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
