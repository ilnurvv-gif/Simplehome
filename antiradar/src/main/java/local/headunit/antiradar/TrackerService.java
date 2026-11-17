package local.headunit.antiradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Следит за GPS и предупреждает: «Впереди камера на 60 через 300 метров». */
public class TrackerService extends Service implements LocationListener {

    /** Состояние для экрана (тот же процесс). */
    public static final class State {
        public volatile boolean running;
        public volatile boolean hasFix;
        public volatile float speedKmh;
        public volatile int camDist = -1;   // м до ближайшей камеры впереди
        public volatile int camLimit;       // 0 = неизвестно
        public volatile String message = "";
        public volatile int cameras;
    }
    public static final State STATE = new State();
    public interface Listener { void onState(); }
    public static volatile Listener listener;
    public static volatile Location LAST;

    private static final int[] LEVELS = {1000, 500, 300}; // м, на которых озвучиваем
    private static final int SEE_DIST = 1200;

    private LocationManager lm;
    private List<Camera> cams = new ArrayList<>();
    private final Map<Integer, Integer> announced = new HashMap<>(); // индекс камеры -> последний озвученный уровень
    private TextToSpeech tts;
    private boolean ttsReady;
    private ToneGenerator tone;
    private WindowManager wm;
    private TextView overlay;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastFix;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Throwable ignore) { }
        try {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new Locale("ru", "RU"));
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                }
            });
        } catch (Throwable ignore) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        cams = CameraDb.load(this);
        STATE.cameras = cams.size();
        announced.clear();
        try {
            lm.removeUpdates(this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            STATE.running = true;
        } catch (SecurityException e) {
            STATE.message = "Нет разрешения на геолокацию";
            stopSelf();
        } catch (Throwable t) {
            STATE.message = "GPS недоступен";
        }
        notifyUi();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        String ch = "antiradar";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(ch, "Антирадар", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("Антирадар РБ работает").setContentText("Слежу за камерами впереди")
                .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, n);
    }

    @Override
    public void onLocationChanged(Location l) {
        lastFix = System.currentTimeMillis();
        LAST = l;
        STATE.hasFix = true;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
        STATE.speedKmh = kmh;
        boolean moving = kmh > 7f && l.hasBearing();
        float heading = l.getBearing();

        int best = -1; float bestD = Float.MAX_VALUE;
        float[] res = new float[2];
        for (int i = 0; i < cams.size(); i++) {
            Camera c = cams.get(i);
            if (Math.abs(c.lat - l.getLatitude()) > 0.015 || Math.abs(c.lon - l.getLongitude()) > 0.025) continue;
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), c.lat, c.lon, res);
            float d = res[0];
            if (d > SEE_DIST) continue;
            if (moving) {
                if (angDiff(heading, res[1]) > 50f) continue;              // камера не по ходу движения
                if (c.dir >= 0) {                                          // камера смотрит вдоль дороги
                    float dd = angDiff(heading, c.dir);
                    if (Math.min(dd, 180f - dd) > 60f) continue;           // камера на другой дороге
                }
            }
            if (d < bestD) { bestD = d; best = i; }
        }
        // забываем камеры, от которых уехали
        java.util.Iterator<Integer> it = announced.keySet().iterator();
        while (it.hasNext()) {
            Camera c0 = cams.get(it.next());
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), c0.lat, c0.lon, res);
            if (res[0] > SEE_DIST + 300) it.remove();
        }

        if (best >= 0) {
            Camera c = cams.get(best);
            int dist = Math.round(bestD / 10f) * 10;
            STATE.camDist = dist;
            STATE.camLimit = c.limit;
            String text = phrase(c.limit, dist);
            STATE.message = text;
            checkAnnounce(best, c, bestD);
            showOverlay(text, c.limit > 0 && kmh > c.limit + 2);
        } else {
            STATE.camDist = -1;
            STATE.camLimit = 0;
            STATE.message = "";
            hideOverlay();
        }
        notifyUi();
    }

    /** «Впереди камера на 60 через 300 метров» */
    static String phrase(int limit, int dist) {
        StringBuilder s = new StringBuilder("Впереди камера");
        if (limit > 0) s.append(" на ").append(limit);
        s.append(" через ").append(dist).append(" ").append(metres(dist));
        return s.toString();
    }

    static String metres(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m100 >= 11 && m100 <= 14) return "метров";
        if (m10 == 1) return "метр";
        if (m10 >= 2 && m10 <= 4) return "метра";
        return "метров";
    }

    private void checkAnnounce(int idx, Camera c, float d) {
        Integer last = announced.get(idx);
        int lastLevel = last == null ? Integer.MAX_VALUE : last;
        for (int lv : LEVELS) {
            if (d <= lv && lv < lastLevel) {
                announced.put(idx, lv);
                speak(phrase(c.limit, lv));
                return;
            }
        }
    }

    private void speak(String text) {
        SharedPreferences p = getSharedPreferences("ar", MODE_PRIVATE);
        if (!p.getBoolean("sound", true)) return;
        try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250); } catch (Throwable ignore) { }
        if (ttsReady) {
            try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cam"); } catch (Throwable ignore) { }
        }
    }

    private static float angDiff(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    // ----- баннер поверх других приложений (навигатора) -----
    private void showOverlay(String text, boolean over) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        ui.post(() -> {
            try {
                if (overlay == null) {
                    overlay = new TextView(this);
                    overlay.setTextSize(26);
                    overlay.setTextColor(Color.WHITE);
                    overlay.setGravity(Gravity.CENTER);
                    overlay.setPadding(40, 18, 40, 18);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = 40;
                    wm.addView(overlay, lp);
                }
                overlay.setText(text);
                overlay.setBackgroundColor(over ? 0xE6C62828 : 0xE61E3A5F);
                overlay.setVisibility(android.view.View.VISIBLE);
            } catch (Throwable ignore) { }
        });
    }

    private void hideOverlay() {
        ui.post(() -> { if (overlay != null) overlay.setVisibility(android.view.View.GONE); });
    }

    private void notifyUi() {
        Listener l = listener;
        if (l != null) ui.post(l::onState);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { STATE.hasFix = false; notifyUi(); }

    @Override
    public void onDestroy() {
        try { lm.removeUpdates(this); } catch (Throwable ignore) { }
        try { if (overlay != null) wm.removeView(overlay); } catch (Throwable ignore) { }
        overlay = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignore) { }
        try { if (tone != null) tone.release(); } catch (Throwable ignore) { }
        STATE.running = false;
        STATE.hasFix = false;
        STATE.camDist = -1;
        notifyUi();
        super.onDestroy();
    }
}
