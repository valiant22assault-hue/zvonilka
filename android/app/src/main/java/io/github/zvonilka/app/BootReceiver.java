package io.github.zvonilka.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки телефона и обновления приложения снова выходим на связь — если уже есть контакты. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        String state = ctx.getSharedPreferences("zv", Context.MODE_PRIVATE).getString("state", null);
        if (state != null && state.contains("\"secret\"")) ZvService.start(ctx);
    }
}
