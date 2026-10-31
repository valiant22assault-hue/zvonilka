package io.github.zvonilka.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Входящий звонок на весь экран, в том числе поверх экрана блокировки. */
public class IncomingCallActivity extends Activity {
    private static WeakReference<IncomingCallActivity> current = new WeakReference<>(null);
    private String callId;
    private float dp;

    static Intent intent(Context ctx, String name, String callId) {
        return new Intent(ctx, IncomingCallActivity.class).putExtra("name", name).putExtra("callId", callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    static void close() {
        IncomingCallActivity a = current.get();
        if (a != null) a.runOnUiThread(a::finish);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        callId = getIntent().getStringExtra("callId");
        if (!ZvService.isRinging(callId)) { finish(); return; }   // звонок уже закончился
        current = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xff24272d);
        getWindow().setNavigationBarColor(0xff24272d);
        dp = getResources().getDisplayMetrics().density;

        String name = getIntent().getStringExtra("name");
        if (name == null || name.trim().isEmpty()) name = "Контакт";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xff24272d);
        root.setPadding(px(24), px(96), px(24), px(72));

        TextView avatar = new TextView(this);
        avatar.setText(name.trim().substring(0, 1).toUpperCase());
        avatar.setTextColor(0xffeef0f3);
        avatar.setTextSize(44);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(circle(0xff3d4352));
        root.addView(avatar, new LinearLayout.LayoutParams(px(120), px(120)));

        TextView title = text(name, 30, 0xffeef0f3, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(24);
        root.addView(title, tp);
        root.addView(text("звонит вам", 17, 0xffa9afb9, false));

        View space = new View(this);
        root.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button(0xffe5484d, 135, "Отклонить", v -> {
            ZvService s = ZvService.instance;
            if (s != null) s.decline(callId);
            else startService(new Intent(this, ZvService.class).setAction(ZvService.ACTION_DECLINE).putExtra(ZvService.EXTRA_CALL, callId));
            finish();
        }));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(px(72), 1));
        buttons.addView(button(0xff3fbf7f, 0, "Ответить", v -> {
            startActivity(MainActivity.acceptIntent(this, callId));
            finish();
        }));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override protected void onDestroy() {
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout button(int color, float rotation, String label, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout disc = new FrameLayout(this);
        disc.setBackground(circle(color));
        disc.setOnClickListener(click);
        disc.setContentDescription(label);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_call);
        icon.setRotation(rotation);
        disc.addView(icon, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        col.addView(disc, new LinearLayout.LayoutParams(px(76), px(76)));
        TextView t = text(label, 14, 0xffa9afb9, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(10);
        col.addView(t, lp);
        return col;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
