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
            handler.postDelayed(this, 30000);
        }
    };

    private GridView grid;
    private AppAdapter adapter;
    private View drawer;
    private LinearLayout dock;
    private TextView greeting;
    private TextView dateView;
    private SharedPreferences prefs;
    private FillVideoView video;
    private View poster;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        grid = findViewById(R.id.grid);
        drawer = findViewById(R.id.drawer);
        dock = findViewById(R.id.dock);
        video = findViewById(R.id.video);
        poster = findViewById(R.id.poster);
        setupVideo();
        setupAudioControls();
        setupLogoButton();
        greeting = findViewById(R.id.greeting);
        dateView = findViewById(R.id.date);

        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(apps.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showAppMenu(apps.get(position));
            return true;
        });

        findViewById(R.id.pill_wifi).setOnClickListener(v -> safeStart(Settings.ACTION_WIFI_SETTINGS));
        findViewById(R.id.pill_vpn).setOnClickListener(v -> safeStart(Settings.ACTION_VPN_SETTINGS));
        findViewById(R.id.btn_bt).setOnClickListener(v -> safeStart(Settings.ACTION_BLUETOOTH_SETTINGS));
        findViewById(R.id.btn_settings).setOnClickListener(v -> safeStart(Settings.ACTION_SETTINGS));
        findViewById(R.id.btn_apps).setOnClickListener(v ->
                drawer.setVisibility(drawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
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
        eqCustom = loadEq();
        if (bassMode != 0) {
            String r = BassController.apply(bassMode, eqCustom);
            if (r.isEmpty()) bassMode = 0;
        }
        paintBass();
        bassBtn.setOnClickListener(v -> {
            int next = bassMode == 3 ? 0 : (bassMode + 1) % 3; // 0 -> 1 -> 2 -> 0
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
        bassBtn.setText(bassMode == 1 ? "BASS" : bassMode == 2 ? "BASS +" : bassMode == 3 ? "EQ" : "BASS");
        bassBtn.setAlpha(on ? 1f : 0.85f);
    }

    // ---------- логотип Hyundai на фоне = кнопка «все приложения» ----------
    // Координаты логотипа в видео 1600x960 (центр 158,847); учитываем center-crop как в FillVideoView.
    private void setupLogoButton() {
        final View root = findViewById(R.id.root);
        final View hit = findViewById(R.id.logo_hit);
        hit.setOnClickListener(v ->
                drawer.setVisibility(drawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;
            float s = Math.max(w / 1600f, h / 960f);
            int bw = Math.round(250 * s), bh = Math.round(135 * s);
            int cx = Math.round(w / 2f + (158 - 800) * s), cy = Math.round(h / 2f + (847 - 480) * s);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hit.getLayoutParams();
            int nl = Math.max(0, cx - bw / 2), nt = Math.max(0, cy - bh / 2);
            if (lp.width != bw || lp.height != bh || lp.leftMargin != nl || lp.topMargin != nt) {
                lp.width = bw; lp.height = bh; lp.leftMargin = nl; lp.topMargin = nt;
                hit.setLayoutParams(lp);
            }
        });
    }

    // ---------- виджет (Яндекс Музыка) ----------
    private static final int REQ_BIND = 501, REQ_CFG = 502;
    private AppWidgetManager awm;
    private AppWidgetHost widgetHost;
    private int pendingId = -1;
    private ComponentName pendingProvider;

    private void setupWidget() {
        awm = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 1024);
        findViewById(R.id.widget_add).setOnClickListener(v -> pickWidget());
        findViewById(R.id.widget_remove).setOnClickListener(v -> {
            int id = prefs.getInt("widget_id", -1);
            if (id != -1) { try { widgetHost.deleteAppWidgetId(id); } catch (Throwable ignore) {} }
            prefs.edit().remove("widget_id").apply();
            showWidget();
        });
        showWidget();
    }

    private void showWidget() {
        FrameLayout hostBox = findViewById(R.id.widget_host);
        View add = findViewById(R.id.widget_add);
        View rm = findViewById(R.id.widget_remove);
        hostBox.removeAllViews();
        int id = prefs.getInt("widget_id", -1);
        AppWidgetProviderInfo info = id == -1 ? null : awm.getAppWidgetInfo(id);
        if (info == null) {
            add.setVisibility(View.VISIBLE);
            rm.setVisibility(View.GONE);
            return;
        }
        try {
            AppWidgetHostView hv = widgetHost.createView(this, id, info);
            hv.setAppWidget(id, info);
            hostBox.addView(hv, new FrameLayout.LayoutParams(-1, -1));
            hv.post(() -> {
                float d = getResources().getDisplayMetrics().density;
                int w = Math.round(hostBox.getWidth() / d), hgt = Math.round(hostBox.getHeight() / d);
                try { hv.updateAppWidgetSize(null, w, hgt, w, hgt); } catch (Throwable ignore) {}
            });
            add.setVisibility(View.GONE);
            rm.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            prefs.edit().remove("widget_id").apply();
            add.setVisibility(View.VISIBLE);
            rm.setVisibility(View.GONE);
        }
    }

    private void pickWidget() {
        final PackageManager pm = getPackageManager();
        final List<AppWidgetProviderInfo> all = awm.getInstalledProviders();
        final List<AppWidgetProviderInfo> music = new ArrayList<>();
        for (AppWidgetProviderInfo i : all) {
            String p = i.provider.getPackageName().toLowerCase(Locale.ROOT);
            if (p.contains("yandex") && (p.contains("music") || p.contains("mus"))) music.add(i);
        }
        final boolean found = !music.isEmpty();
        final List<AppWidgetProviderInfo> list = found ? music : all;
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
                .setTitle(found ? "Виджет Яндекс Музыки" : "Яндекс Музыка не найдена — выберите любой виджет")
                .setItems(names, (d, which) -> bindWidget(list.get(which).provider))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void bindWidget(ComponentName provider) {
        int id = widgetHost.allocateAppWidgetId();
        boolean ok = false;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, provider); } catch (Throwable ignore) {}
        if (ok) {
            afterBind(id);
        } else {
            pendingId = id;
            pendingProvider = provider;
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            try {
                startActivityForResult(i, REQ_BIND);
            } catch (Throwable t) {
                widgetHost.deleteAppWidgetId(id);
                Toast.makeText(this, "Система не разрешила добавить виджет", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void afterBind(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CFG, null);
                pendingId = id;
                return;
            } catch (Throwable ignore) { /* без настройки */ }
        }
        saveWidget(id);
    }

    private void saveWidget(int id) {
        int old = prefs.getInt("widget_id", -1);
        if (old != -1 && old != id) { try { widgetHost.deleteAppWidgetId(old); } catch (Throwable ignore) {} }
        prefs.edit().putInt("widget_id", id).apply();
        showWidget();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BIND) {
            if (res == RESULT_OK && pendingId != -1) afterBind(pendingId);
            else if (pendingId != -1) { widgetHost.deleteAppWidgetId(pendingId); pendingId = -1; }
        } else if (req == REQ_CFG) {
            if (res == RESULT_OK && pendingId != -1) saveWidget(pendingId);
            else if (pendingId != -1) { widgetHost.deleteAppWidgetId(pendingId); pendingId = -1; }
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

    private void setupVideo() {
        video.setSource(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.bg),
                new FillVideoView.Callback() {
                    @Override
                    public void onFirstFrame() {
                        runOnUiThread(() -> poster.setVisibility(View.GONE));
                    }

                    @Override
                    public void onFailed() {
                        runOnUiThread(() -> poster.setVisibility(View.VISIBLE)); // остаётся картинка
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (volBar != null) { volBar.refresh(); registerReceiver(volRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION")); }
        try { video.start(); } catch (Exception ignored) { }
        handler.removeCallbacks(tick);
        handler.post(tick);
        loadAppsAsync();
    }

    @Override
    protected void onPause() {
        try { video.pause(); } catch (Exception ignored) { }
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
                adapter.notifyDataSetChanged();
                rebuildDock();
            });
        }).start();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }
            AppItem item = apps.get(position);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(item.icon);
            ((TextView) v.findViewById(R.id.label)).setText(item.label);
            return v;
        }
    }
}
