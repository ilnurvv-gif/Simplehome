package local.headunit.antiradar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Экран «Антирадар РБ»: скорость, ближайшая камера впереди, управление. */
public class MainActivity extends Activity implements TrackerService.Listener {

    private TextView speed, camBig, camMsg, limitSign, status, baseInfo, btnStart, btnSound, btnUpdate;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ar", MODE_PRIVATE);
        setContentView(build());
        refreshBaseInfo();
        onState();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView t = tv(s, 18, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) dp(22), (int) dp(14), (int) dp(22), (int) dp(14));
        setBtnBg(t, 0x33FFFFFF);
        t.setOnClickListener(l);
        return t;
    }

    private void setBtnBg(TextView t, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(26)); g.setStroke((int) dp(1), 0x66CFE2FF);
        t.setBackground(g);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0A0E1C, 0xFF132447});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) dp(28), (int) dp(20), (int) dp(28), (int) dp(20));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // шапка
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("Антирадар РБ", 24, Color.WHITE, true);
        status = tv("", 16, 0xFFCFE2FF, false);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(status);
        col.addView(top);

        // центр
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        col.addView(center, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        speed = tv("0", 96, Color.WHITE, true);
        left.addView(speed);
        left.addView(tv("км/ч", 20, 0xFFCFE2FF, false));
        center.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        limitSign = tv("", 40, Color.BLACK, true);
        limitSign.setGravity(Gravity.CENTER);
        GradientDrawable sign = new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL); sign.setColor(Color.WHITE); sign.setStroke((int) dp(8), 0xFFD32F2F);
        limitSign.setBackground(sign);
        right.addView(limitSign, new LinearLayout.LayoutParams((int) dp(110), (int) dp(110)));
        camBig = tv("", 60, Color.WHITE, true);
        right.addView(camBig);
        camMsg = tv("", 22, 0xFFCFE2FF, false);
        camMsg.setGravity(Gravity.CENTER);
        right.addView(camMsg);
        center.addView(right, new LinearLayout.LayoutParams(0, -2, 1.3f));

        baseInfo = tv("", 14, 0xAACFE2FF, false);
        col.addView(baseInfo);

        // кнопки
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) dp(12), 0, 0);
        btnStart = button("СТАРТ", v -> toggle());
        btnUpdate = button("Обновить базу", v -> updateBase());
        TextView add = button("+ Камера здесь", v -> addHere());
        btnSound = button("", v -> {
            prefs.edit().putBoolean("sound", !prefs.getBoolean("sound", true)).apply();
            paintSound();
        });
        TextView ov = button("Поверх окон", v -> askOverlay());
        for (TextView t : new TextView[]{btnStart, btnUpdate, add, btnSound, ov}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins((int) dp(5), 0, (int) dp(5), 0);
            row.addView(t, lp);
        }
        col.addView(row);
        paintSound();
        return root;
    }

    private void paintSound() {
        btnSound.setText(prefs.getBoolean("sound", true) ? "Звук: вкл" : "Звук: выкл");
    }

    private void toggle() {
        if (TrackerService.STATE.running) {
            stopService(new Intent(this, TrackerService.class));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            String[] p = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(p, 7);
            return;
        }
        startTracker();
    }

    private void startTracker() {
        if (CameraDb.load(this).isEmpty()) {
            Toast.makeText(this, "База пуста: нажмите «Обновить базу» (нужен интернет)", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(this, TrackerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Не удалось запустить службу", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else if (code == 7) {
            Toast.makeText(this, "Без доступа к геолокации приложение не работает", Toast.LENGTH_LONG).show();
        }
    }

    private void updateBase() {
        btnUpdate.setText("Загрузка…");
        btnUpdate.setEnabled(false);
        new Thread(() -> {
            String msg;
            try {
                int n = CameraDb.updateFromOsm(this);
                prefs.edit().putLong("osm_time", System.currentTimeMillis()).apply();
                msg = "Загружено камер: " + n;
            } catch (Exception e) {
                msg = "Не удалось загрузить базу: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                btnUpdate.setText("Обновить базу");
                btnUpdate.setEnabled(true);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refreshBaseInfo();
                if (TrackerService.STATE.running) startTracker(); // перечитать базу
            });
        }).start();
    }

    private void addHere() {
        Location l = TrackerService.LAST;
        if (l == null || !TrackerService.STATE.hasFix) {
            Toast.makeText(this, "Нет GPS-сигнала. Запустите слежение и дождитесь фикса", Toast.LENGTH_LONG).show();
            return;
        }
        int dir = (l.hasBearing() && l.hasSpeed() && l.getSpeed() * 3.6f > 7f) ? Math.round(l.getBearing()) : -1;
        CameraDb.addUser(this, new Camera(l.getLatitude(), l.getLongitude(), 0, dir, true));
        Toast.makeText(this, "Камера добавлена в вашу базу", Toast.LENGTH_SHORT).show();
        refreshBaseInfo();
        if (TrackerService.STATE.running) startTracker();
    }

    private void askOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(this, "Откройте Настройки → Приложения → Антирадар РБ → Поверх других окон", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Баннер поверх навигатора уже разрешён", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshBaseInfo() {
        int all = CameraDb.load(this).size(), mine = CameraDb.countUser(this);
        long t = prefs.getLong("osm_time", 0);
        String when = t == 0 ? "ещё не загружалась" : "обновлена " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(t));
        baseInfo.setText("База: " + all + " камер (из них ваших " + mine + "), " + when
                + ".  Данные OpenStreetMap © участники, ODbL. База может быть неполной.");
    }

    @Override
    public void onState() {
        TrackerService.State s = TrackerService.STATE;
        btnStart.setText(s.running ? "СТОП" : "СТАРТ");
        setBtnBg(btnStart, s.running ? 0x99C62828 : 0x992E7D32);
        status.setText(!s.running ? "выключен" : s.hasFix ? "GPS: есть сигнал" : "GPS: ищу спутники…");
        speed.setText(String.valueOf(Math.round(s.speedKmh)));
        boolean over = s.camDist >= 0 && s.camLimit > 0 && s.speedKmh > s.camLimit + 2;
        speed.setTextColor(over ? 0xFFFF5252 : Color.WHITE);
        if (s.camDist >= 0) {
            limitSign.setVisibility(s.camLimit > 0 ? View.VISIBLE : View.GONE);
            limitSign.setText(String.valueOf(s.camLimit));
            camBig.setText(s.camDist + " м");
            camMsg.setText(s.message);
        } else {
            limitSign.setVisibility(View.GONE);
            camBig.setText(s.running ? "Камер впереди нет" : "");
            camBig.setTextSize(30);
            camMsg.setText(s.running ? "" : "Нажмите СТАРТ");
        }
        if (s.camDist >= 0) camBig.setTextSize(60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TrackerService.listener = this;
        refreshBaseInfo();
        onState();
    }

    @Override
    protected void onPause() {
        TrackerService.listener = null;
        super.onPause();
    }
}
