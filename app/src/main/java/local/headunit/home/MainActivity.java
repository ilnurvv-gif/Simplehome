package local.headunit.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import android.Manifest;
import android.graphics.Rect;
import android.widget.EditText;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.ConnectivityManager;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Простой лаунчер: анимированный фон, приветствие, часы, панель приложений.
 * Кнопка с сеткой открывает полный список. Долгое нажатие на приложение:
 * закрепить на панели / убрать / открыть "О приложении".
 */
public class MainActivity extends Activity {

    private static final int DOCK_DEFAULT = 7;
    private static final int DOCK_MAX = 8;

    private static class AppItem {
        String label;
        String pkg;
        Drawable icon;
    }

    private final List<AppItem> apps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            paintVpn();
            handler.postDelayed(this, 30000);
        }
    };

    private android.widget.GridLayout grid;
    private View drawer;
    private LinearLayout dock;
    private TextView greeting;
    private TextView dateView;
    private SharedPreferences prefs;
    private ImageView poster;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        grid = findViewById(R.id.grid);
        drawer = findViewById(R.id.drawer);
        dock = findViewById(R.id.dock);
        poster = findViewById(R.id.poster);
        setupVideo();
        setupAudioControls();
        setupLogoButton();
        greeting = findViewById(R.id.greeting);
        dateView = findViewById(R.id.date);

        drawer.setOnClickListener(v -> drawer.setVisibility(View.GONE));   // тап вне окна закрывает

        findViewById(R.id.pill_wifi).setOnClickListener(v -> safeStart(Settings.ACTION_WIFI_SETTINGS));
        setupVpn();
        findViewById(R.id.btn_bt).setOnClickListener(v -> safeStart(Settings.ACTION_BLUETOOTH_SETTINGS));
        findViewById(R.id.btn_settings).setOnClickListener(v -> safeStart(Settings.ACTION_SETTINGS));
        findViewById(R.id.btn_apps).setOnClickListener(v ->
                { drawer.setVisibility(drawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        setupWidget();
    }

    private VolumeBar volBar;
    private TextView bassBtn;
    private int bassMode;
    private short[] eqCustom;
    private final BroadcastReceiver volRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (volBar != null) volBar.refresh(); }
    };

    private void setupAudioControls() {
        volBar = findViewById(R.id.vol);
        bassBtn = findViewById(R.id.btn_bass);
        bassMode = prefs.getInt("bass_mode", 0);
        if (bassMode == 2) bassMode = 1; // старый «BASS+» больше нет
        eqCustom = loadEq();
        if (bassMode != 0) {
            String r = BassController.apply(bassMode, eqCustom);
            if (r.isEmpty()) bassMode = 0;
        }
        paintBass();
        bassBtn.setOnClickListener(v -> {
            int next = bassMode == 0 ? 1 : 0; // выкл <-> BASS
            String r = BassController.apply(next, eqCustom);
            if (next != 0 && r.isEmpty()) {
                Toast.makeText(this, "Эта магнитола не пускает аудиоэффекты в лаунчер", Toast.LENGTH_LONG).show();
                BassController.apply(0, null);
                next = 0;
            } else if (next != 0) {
                Toast.makeText(this, "Эффекты: " + r, Toast.LENGTH_SHORT).show();
            }
            bassMode = next;
            prefs.edit().putInt("bass_mode", bassMode).apply();
            paintBass();
        });
        bassBtn.setOnLongClickListener(v -> {
            EqDialog.show(this, eqCustom, levels -> {
                eqCustom = levels;
                saveEq(levels);
                bassMode = 3;
                BassController.apply(3, eqCustom);
                prefs.edit().putInt("bass_mode", 3).apply();
                paintBass();
            });
            return true;
        });
    }

    private short[] loadEq() {
        String s = prefs.getString("eq_custom", "");
        if (s.isEmpty()) return null;
        String[] p = s.split(",");
        short[] r = new short[p.length];
        try { for (int i = 0; i < p.length; i++) r[i] = Short.parseShort(p[i]); } catch (Exception e) { return null; }
        return r;
    }

    private void saveEq(short[] lv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lv.length; i++) { if (i > 0) sb.append(','); sb.append(lv[i]); }
        prefs.edit().putString("eq_custom", sb.toString()).apply();
    }

    private void paintBass() {
        boolean on = bassMode != 0;
        bassBtn.setBackgroundResource(on ? R.drawable.bass_on : R.drawable.bass_off);
        bassBtn.setText(bassMode == 3 ? "EQ" : "BASS");
        bassBtn.setAlpha(on ? 1f : 0.85f);
    }

    // ---------- логотип Hyundai на фоне = кнопка «все приложения» ----------
    // Координаты логотипа в видео 1600x960 (центр 158,847); учитываем center-crop как в FillVideoView.
    private void setupLogoButton() {
        final View root = findViewById(R.id.root);
        final View hit = findViewById(R.id.logo_hit);
        hit.setOnClickListener(v ->
                { drawer.setVisibility(drawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeLogoHit());
    }

    private void placeLogoHit() {
        View root = findViewById(R.id.root);
        View hit = findViewById(R.id.logo_hit);
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        float[] g = LOGO[bgIndex];
        float s = Math.max(w / 1600f, h / 960f);
        int bw = Math.round(g[2] * s), bh = Math.round(g[3] * s);
        int cx = Math.round(w / 2f + (g[0] - 800) * s), cy = Math.round(h / 2f + (g[1] - 480) * s);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hit.getLayoutParams();
        int nl = Math.max(0, cx - bw / 2), nt = Math.max(0, cy - bh / 2);
        if (lp.width != bw || lp.height != bh || lp.leftMargin != nl || lp.topMargin != nt) {
            lp.width = bw; lp.height = bh; lp.leftMargin = nl; lp.topMargin = nt;
            hit.setLayoutParams(lp);
        }
    }

    // ---------- VPN (Amnezia) ----------
    private static final String PKG_AWG = "org.amnezia.awg";   // AmneziaWG: умеет внешнее управление
    private static final String PKG_AMN = "org.amnezia.vpn";   // Amnezia VPN: внешнего управления нет
    private TextView pillVpn;

    private void setupVpn() {
        pillVpn = findViewById(R.id.pill_vpn);
        pillVpn.setOnClickListener(v -> toggleVpn());
        pillVpn.setOnLongClickListener(v -> { safeStart(Settings.ACTION_VPN_SETTINGS); return true; });
        paintVpn();
    }

    private boolean vpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignore) { }
        return false;
    }

    private void paintVpn() {
        if (pillVpn == null) return;
        boolean on = vpnActive();
        pillVpn.setBackgroundResource(on ? R.drawable.bass_on : R.drawable.pill_bg);
        pillVpn.setText(on ? "VPN ON" : "VPN");
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private void toggleVpn() {
        final boolean on = vpnActive();
        if (installed(PKG_AWG)) {
            String tunnel = prefs.getString("awg_tunnel", "");
            if (tunnel.isEmpty()) {
                final EditText et = new EditText(this);
                et.setHint("имя туннеля в AmneziaWG");
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Имя туннеля")
                        .setMessage("Введите имя туннеля из AmneziaWG. В её настройках должно быть включено «Разрешить управление из других приложений».")
                        .setView(et)
                        .setPositiveButton("OK", (d, w) -> {
                            String n = et.getText().toString().trim();
                            if (!n.isEmpty()) { prefs.edit().putString("awg_tunnel", n).apply(); sendAwg(n, !vpnActive()); }
                        })
                        .setNegativeButton("Отмена", null).show();
            } else {
                sendAwg(tunnel, !on);
            }
        } else if (installed(PKG_AMN)) {
            Intent i = getPackageManager().getLaunchIntentForPackage(PKG_AMN);
            if (i != null) {
                Toast.makeText(this, on ? "Откройте Amnezia и нажмите «Отключить»" : "Откройте Amnezia и нажмите «Подключить»", Toast.LENGTH_LONG).show();
                try { startActivity(i); } catch (Throwable ignore) { }
            }
        } else {
            Toast.makeText(this, "Amnezia не установлена", Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::paintVpn, 2500);
        handler.postDelayed(this::paintVpn, 6000);
    }

    private void sendAwg(String tunnel, boolean up) {
        Intent i = new Intent(up ? PKG_AWG + ".action.SET_TUNNEL_UP" : PKG_AWG + ".action.SET_TUNNEL_DOWN");
        i.setPackage(PKG_AWG);
        i.putExtra("tunnel", tunnel);
        try {
            sendBroadcast(i);
            Toast.makeText(this, (up ? "Подключаю " : "Отключаю ") + tunnel, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Не удалось отправить команду в AmneziaWG", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- виджет (Яндекс Музыка) ----------
    private static final class Slot {
        final String prefKey, title;
        final int reqBind, reqCfg;
        final String[] hints;
        FrameLayout box;
        View add, rm, move, frame;
        int pendingId = -1;

        Slot(String prefKey, String title, int reqBind, int reqCfg, String... hints) {
            this.prefKey = prefKey; this.title = title;
            this.reqBind = reqBind; this.reqCfg = reqCfg; this.hints = hints;
        }

        boolean matches(String pkg) {
            String p = pkg.toLowerCase(Locale.ROOT);
            for (String h : hints) if (p.contains(h)) return true;
            return false;
        }
    }

    private AppWidgetManager awm;
    private AppWidgetHost widgetHost;
    private Slot[] slots;

    private void setupWidget() {
        awm = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 1024);
        slots = new Slot[]{
                new Slot("widget_id", "Яндекс Музыки", 501, 502, "yandex.music", "music.yandex", "yandex.mus"),
                new Slot("widget_id_maps", "Яндекс Карт", 503, 504, "yandex.yandexmaps", "yandexmaps", "yandex.maps")
        };
        int[][] ids = {
                {R.id.widget_host, R.id.widget_add, R.id.widget_remove, R.id.widget_move, R.id.widget_slot},
                {R.id.widget_host2, R.id.widget_add2, R.id.widget_remove2, R.id.widget_move2, R.id.widget_slot2}
        };
        for (int i = 0; i < slots.length; i++) {
            final Slot s = slots[i];
            s.box = findViewById(ids[i][0]);
            s.add = findViewById(ids[i][1]);
            s.rm = findViewById(ids[i][2]);
            s.move = findViewById(ids[i][3]);
            s.frame = findViewById(ids[i][4]);
            makeDraggable(s);
            s.add.setOnClickListener(v -> pickWidget(s));
            s.rm.setOnClickListener(v -> {
                int id = prefs.getInt(s.prefKey, -1);
                if (id != -1) { try { widgetHost.deleteAppWidgetId(id); } catch (Throwable ignore) {} }
                prefs.edit().remove(s.prefKey).apply();
                showWidget(s);
            });
            showWidget(s);
        }
    }

    /** Кнопка ✥ в углу окна виджета: тянем — окно двигается, позиция запоминается. */
    private void makeDraggable(final Slot s) {
        final String kx = s.prefKey + "_tx", ky = s.prefKey + "_ty";
        s.frame.post(() -> {
            s.frame.setTranslationX(prefs.getFloat(kx, 0f));
            s.frame.setTranslationY(prefs.getFloat(ky, 0f));
            clampSlot(s);
        });
        final float[] st = new float[4];
        s.move.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY();
                    st[2] = s.frame.getTranslationX(); st[3] = s.frame.getTranslationY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    s.frame.setTranslationX(st[2] + e.getRawX() - st[0]);
                    s.frame.setTranslationY(st[3] + e.getRawY() - st[1]);
                    clampSlot(s);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs.edit().putFloat(kx, s.frame.getTranslationX())
                            .putFloat(ky, s.frame.getTranslationY()).apply();
                    return true;
            }
            return false;
        });
    }

    private void clampSlot(Slot s) {
        View root = findViewById(R.id.root);
        View f = s.frame;
        if (root == null || root.getWidth() == 0) return;
        float tx = Math.max(-f.getLeft(), Math.min(root.getWidth() - f.getRight(), f.getTranslationX()));
        float ty = Math.max(-f.getTop(), Math.min(root.getHeight() - f.getBottom(), f.getTranslationY()));
        f.setTranslationX(tx); f.setTranslationY(ty);
    }

    private void showWidget(final Slot s) {
        s.box.removeAllViews();
        int id = prefs.getInt(s.prefKey, -1);
        AppWidgetProviderInfo info = id == -1 ? null : awm.getAppWidgetInfo(id);
        if (info == null) {
            s.add.setVisibility(View.VISIBLE);
            s.rm.setVisibility(View.GONE);
            return;
        }
        try {
            final AppWidgetHostView hv = widgetHost.createView(this, id, info);
            hv.setAppWidget(id, info);
            s.box.addView(hv, new FrameLayout.LayoutParams(-1, -1));
            hv.post(() -> {
                float d = getResources().getDisplayMetrics().density;
                int w = Math.round(s.box.getWidth() / d), hgt = Math.round(s.box.getHeight() / d);
                try { hv.updateAppWidgetSize(null, w, hgt, w, hgt); } catch (Throwable ignore) {}
            });
            s.add.setVisibility(View.GONE);
            s.rm.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            prefs.edit().remove(s.prefKey).apply();
            s.add.setVisibility(View.VISIBLE);
            s.rm.setVisibility(View.GONE);
        }
    }

    private void pickWidget(final Slot s) {
        final PackageManager pm = getPackageManager();
        final List<AppWidgetProviderInfo> all = awm.getInstalledProviders();
        final List<AppWidgetProviderInfo> hit = new ArrayList<>();
        for (AppWidgetProviderInfo i : all) if (s.matches(i.provider.getPackageName())) hit.add(i);
        final boolean found = !hit.isEmpty();
        final List<AppWidgetProviderInfo> list = found ? hit : all;
        if (list.isEmpty()) {
            Toast.makeText(this, "На магнитоле нет ни одного виджета", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            AppWidgetProviderInfo w = list.get(i);
            String app;
            try { app = pm.getApplicationLabel(pm.getApplicationInfo(w.provider.getPackageName(), 0)).toString(); }
            catch (Exception e) { app = w.provider.getPackageName(); }
            names[i] = app + " — " + w.loadLabel(pm);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(found ? "Виджет " + s.title : s.title + " не найдены — выберите любой виджет")
                .setItems(names, (d, which) -> bindWidget(s, list.get(which).provider))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void bindWidget(Slot s, ComponentName provider) {
        int id = widgetHost.allocateAppWidgetId();
        boolean ok = false;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, provider); } catch (Throwable ignore) {}
        if (ok) {
            afterBind(s, id);
        } else {
            s.pendingId = id;
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            try {
                startActivityForResult(i, s.reqBind);
            } catch (Throwable t) {
                widgetHost.deleteAppWidgetId(id);
                s.pendingId = -1;
                Toast.makeText(this, "Система не разрешила добавить виджет", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void afterBind(Slot s, int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                s.pendingId = id;
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, s.reqCfg, null);
                return;
            } catch (Throwable ignore) { /* без настройки */ }
        }
        saveWidget(s, id);
    }

    private void saveWidget(Slot s, int id) {
        int old = prefs.getInt(s.prefKey, -1);
        if (old != -1 && old != id) { try { widgetHost.deleteAppWidgetId(old); } catch (Throwable ignore) {} }
        s.pendingId = -1;
        prefs.edit().putInt(s.prefKey, id).apply();
        showWidget(s);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (slots == null) return;
        for (Slot s : slots) {
            if (req == s.reqBind) {
                if (res == RESULT_OK && s.pendingId != -1) afterBind(s, s.pendingId);
                else if (s.pendingId != -1) { widgetHost.deleteAppWidgetId(s.pendingId); s.pendingId = -1; }
            } else if (req == s.reqCfg) {
                if (res == RESULT_OK && s.pendingId != -1) saveWidget(s, s.pendingId);
                else if (s.pendingId != -1) { widgetHost.deleteAppWidgetId(s.pendingId); s.pendingId = -1; }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { widgetHost.startListening(); } catch (Throwable ignore) {}
    }

    @Override
    protected void onStop() {
        try { widgetHost.stopListening(); } catch (Throwable ignore) {}
        super.onStop();
    }

    // ---------- фоны: свайп влево/вправо меняет картинку (статичные, без анимации) ----------
    private static final int[] POSTER = {R.drawable.poster_desert, R.drawable.poster_snow};
    // кнопка «все приложения» на картинке: центр x, y и размер (в координатах картинки 1600x960)
    private static final float[][] LOGO = {{158, 772, 250, 135}, {159, 772, 250, 135}};
    private int bgIndex;
    private GestureDetector swipe;

    private void setupVideo() {
        bgIndex = Math.max(0, Math.min(POSTER.length - 1, prefs.getInt("bg_index", 0)));
        poster.setImageResource(POSTER[bgIndex]);
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < 140 || Math.abs(dx) < 2f * Math.abs(dy) || Math.abs(vx) < 300) return false;
                if (drawer.getVisibility() == View.VISIBLE) return false;
                int[] busy = {R.id.widget_slot, R.id.widget_slot2, R.id.right_col, R.id.dock};
                for (int id : busy) if (hit(findViewById(id), e1)) return false;
                changeBackground(dx < 0 ? 1 : -1);
                return true;
            }
        });
    }

    private boolean hit(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r)) return false;
        return r.contains((int) e.getRawX(), (int) e.getRawY());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void changeBackground(int dir) {
        bgIndex = (bgIndex + dir + POSTER.length) % POSTER.length;
        prefs.edit().putInt("bg_index", bgIndex).apply();
        poster.setImageResource(POSTER[bgIndex]);
        poster.setAlpha(0f);
        poster.animate().alpha(1f).setDuration(220).start();
        placeLogoHit();
    }

    // ---------- скорость по GPS ----------
    private TextView speedTv;
    private LocationManager speedLm;
    private final SpeedCalc speedCalc = new SpeedCalc();
    private long lastSpeedFix;
    private boolean askedLoc;
    private final LocationListener speedListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            lastSpeedFix = System.currentTimeMillis();
            float raw = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
            speedTv.setText(String.valueOf(Math.round(speedCalc.update(raw, lastSpeedFix))));
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { speedTv.setText("--"); }
    };
    private final Runnable speedWatch = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastSpeedFix > 6000) { speedTv.setText("--"); speedCalc.reset(); }
            handler.postDelayed(this, 2000);
        }
    };

    private void startSpeed() {
        speedTv = findViewById(R.id.speed);
        speedTv.setOnLongClickListener(v -> {      // долгое нажатие на скорость: отчёт «что отдаёт CAN»
            TextView t = new TextView(this);
            t.setText(CarProbe.report(this)); t.setTextIsSelectable(true);
            t.setTextSize(13); t.setPadding(40, 20, 40, 20);
            android.widget.ScrollView sv = new android.widget.ScrollView(this); sv.addView(t);
            new android.app.AlertDialog.Builder(this).setTitle("CAN / авто: разведка").setView(sv)
                    .setPositiveButton("Закрыть", null).show();
            return true;
        });
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!askedLoc) { askedLoc = true; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 21); }
            return;
        }
        try {
            speedLm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            speedLm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, speedListener, Looper.getMainLooper());
            handler.removeCallbacks(speedWatch);
            handler.post(speedWatch);
        } catch (Throwable ignore) { }
    }

    private void stopSpeed() {
        handler.removeCallbacks(speedWatch);
        try { if (speedLm != null) speedLm.removeUpdates(speedListener); } catch (Throwable ignore) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 21) startSpeed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (volBar != null) { volBar.refresh(); registerReceiver(volRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION")); }
        paintVpn();
        startSpeed();
        handler.removeCallbacks(tick);
        handler.post(tick);
        loadAppsAsync();
    }

    @Override
    protected void onPause() {
        stopSpeed();
        handler.removeCallbacks(tick);
        try { unregisterReceiver(volRx); } catch (Throwable ignore) {}
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        drawer.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) {
            drawer.setVisibility(View.GONE);
        }
        // на главном экране "назад" ничего не делает
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (hour < 5) g = "Доброй ночи";
        else if (hour < 12) g = "Доброе утро";
        else if (hour < 18) g = "Добрый день";
        else g = "Добрый вечер";
        greeting.setText(g);

        String d = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date());
        if (!d.isEmpty()) d = d.substring(0, 1).toUpperCase(new Locale("ru")) + d.substring(1);
        dateView.setText(d);
    }

    private void safeStart(String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Недоступно на этой магнитоле", Toast.LENGTH_SHORT).show();
        }
    }

    private void launch(AppItem item) {
        Intent li = getPackageManager().getLaunchIntentForPackage(item.pkg);
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(li);
        }
        drawer.setVisibility(View.GONE);
    }

    // ---------- панель приложений ----------

    private List<String> getPinned() {
        String s = prefs.getString("pinned", "");
        List<String> list = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String p : s.split("\n")) {
                if (!p.isEmpty()) list.add(p);
            }
        }
        return list;
    }

    private void setPinned(List<String> list) {
        prefs.edit().putString("pinned", TextUtils.join("\n", list)).apply();
    }

    private AppItem findApp(String pkg) {
        for (AppItem a : apps) {
            if (a.pkg.equals(pkg)) return a;
        }
        return null;
    }

    private List<AppItem> dockItems() {
        List<AppItem> result = new ArrayList<>();
        for (String pkg : getPinned()) {
            AppItem a = findApp(pkg);
            if (a != null) result.add(a);
        }
        if (result.isEmpty()) {
            for (int i = 0; i < apps.size() && i < DOCK_DEFAULT; i++) result.add(apps.get(i));
        }
        return result;
    }

    private void rebuildDock() {
        dock.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AppItem item : dockItems()) {
            View v = inflater.inflate(R.layout.item_dock, dock, false);
            ((ImageView) v.findViewById(R.id.dock_icon)).setImageDrawable(item.icon);
            ((TextView) v.findViewById(R.id.dock_label)).setText(item.label);
            v.setOnClickListener(x -> launch(item));
            v.setOnLongClickListener(x -> {
                showAppMenu(item);
                return true;
            });
            dock.addView(v);
        }
    }

    private void showAppMenu(final AppItem item) {
        final List<String> pinned = getPinned();
        if (pinned.isEmpty()) {
            for (AppItem a : dockItems()) pinned.add(a.pkg);
        }
        final boolean isPinned = pinned.contains(item.pkg);
        String[] options = {
                isPinned ? "Убрать с панели" : "Закрепить на панели",
                "О приложении (отключить / остановить)"
        };
        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (isPinned) {
                            pinned.remove(item.pkg);
                        } else {
                            if (pinned.size() >= DOCK_MAX) {
                                Toast.makeText(this, "На панели максимум " + DOCK_MAX, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            pinned.add(item.pkg);
                        }
                        setPinned(pinned);
                        rebuildDock();
                    } else {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + item.pkg));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                })
                .show();
    }

    // ---------- загрузка списка приложений ----------

    private void loadAppsAsync() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

            List<AppItem> result = new ArrayList<>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                AppItem item = new AppItem();
                item.pkg = pkg;
                item.label = String.valueOf(ri.loadLabel(pm));
                item.icon = ri.loadIcon(pm);
                result.add(item);
            }
            Collections.sort(result, (a, b) ->
                    a.label.toLowerCase(Locale.getDefault())
                            .compareTo(b.label.toLowerCase(Locale.getDefault())));

            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(result);
                rebuildGrid();
                rebuildDock();
            });
        }).start();
    }

    /** Список приложений — обычные вьюхи в ScrollView (без переиспользования ячеек GridView). */
    private void rebuildGrid() {
        grid.removeAllViews();
        ((TextView) findViewById(R.id.apps_count)).setText(apps.size() + " прилож.");
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < apps.size(); i++) {
            final AppItem item = apps.get(i);
            View v = inf.inflate(R.layout.item_app, grid, false);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(item.icon);
            ((TextView) v.findViewById(R.id.label)).setText(item.label);
            v.setClickable(true);
            v.setOnClickListener(x -> launch(item));
            v.setOnLongClickListener(x -> { showAppMenu(item); return true; });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            int m = (int) (5 * getResources().getDisplayMetrics().density);
            lp.setMargins(m, m, m, m);
            grid.addView(v, lp);
        }
    }
}
