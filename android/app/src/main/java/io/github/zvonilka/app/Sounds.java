package io.github.zvonilka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * Выбранные мелодия звонка и звук сообщений. Не задано — системные по умолчанию, пустая строка — без звука.
 * Играем через Ringtone: если своей мелодии приложению не прочитать, её сыграет система.
 */
final class Sounds {
    static final String CALL = "ringtone", MSG = "msgSound";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("zv", Context.MODE_PRIVATE);
    }

    static Uri get(Context c, String kind) {
        String s = prefs(c).getString(kind, null);
        if (s == null) return CALL.equals(kind) ? Settings.System.DEFAULT_RINGTONE_URI : Settings.System.DEFAULT_NOTIFICATION_URI;
        return s.isEmpty() ? null : Uri.parse(s);
    }

    static void set(Context c, String kind, Uri u) {
        prefs(c).edit().putString(kind, u == null ? "" : u.toString()).apply();
    }

    static boolean vibrate(Context c) {
        return prefs(c).getBoolean("vibrate", true);
    }

    static void setVibrate(Context c, boolean on) {
        prefs(c).edit().putBoolean("vibrate", on).apply();
    }

    static String title(Context c, Uri u) {
        if (u == null) return "Без звука";
        try {
            Ringtone r = RingtoneManager.getRingtone(c, u);
            String t = r != null ? r.getTitle(c) : null;
            return t != null && !t.isEmpty() ? t : "Своя мелодия";
        } catch (Exception e) {
            return "Своя мелодия";
        }
    }

    static Ringtone play(Context c, Uri u, int usage) {
        if (u == null) return null;
        try {
            Ringtone r = RingtoneManager.getRingtone(c, u);
            if (r == null) return null;
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
