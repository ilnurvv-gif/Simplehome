package local.headunit.home;

import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

/**
 * Глобальная обработка звука (audio session 0).
 * Режимы: 0 выкл, 1 BASS, 3 свой эквалайзер.
 * Чтобы музыка не становилась тише, поднимаем низы умеренно (а не в потолок),
 * чуть приподнимаем верх (V-образная кривая, чёткость) и добавляем LoudnessEnhancer,
 * который возвращает громкость и защищает от клиппинга.
 */
public final class BassController {
    public static final int OFF = 0, BASS = 1, CUSTOM = 3;

    private static BassBoost boost;
    private static Equalizer eq;
    private static LoudnessEnhancer loud;

    private BassController() {}

    /** Усиление полосы в дБ: мягкие, «приятные» низы и лёгкий подъём верха для чёткости. */
    private static float presetDb(int hz) {
        if (hz < 100) return 4.5f;
        if (hz < 300) return 3f;
        if (hz < 1500) return 0f;
        if (hz < 6000) return 0.5f;
        return 1.5f;
    }

    /**
     * @param custom уровни полос в мБ для режима CUSTOM (иначе null)
     * @return строка с названиями сработавших эффектов; пусто, если ни один не включился
     */
    public static synchronized String apply(int mode, short[] custom) {
        release();
        if (mode == OFF) return "off";
        StringBuilder ok = new StringBuilder();
        try {
            eq = new Equalizer(1000, 0);
            short[] r = eq.getBandLevelRange();
            int n = eq.getNumberOfBands();
            for (short b = 0; b < n; b++) {
                int mb;
                if (mode == CUSTOM && custom != null && b < custom.length) {
                    mb = custom[b];
                } else {
                    mb = Math.round(presetDb(eq.getCenterFreq(b) / 1000) * 100f);
                }
                mb = Math.max(r[0], Math.min(r[1], mb));
                eq.setBandLevel(b, (short) mb);
            }
            eq.setEnabled(true);
            ok.append("EQ ");
        } catch (Throwable t) {
            try { if (eq != null) eq.release(); } catch (Throwable ignore) {}
            eq = null;
        }
        if (mode != CUSTOM) {
            try {
                boost = new BassBoost(1000, 0);
                if (boost.getStrengthSupported()) boost.setStrength((short) 300);
                boost.setEnabled(true);
                ok.append("BassBoost ");
            } catch (Throwable t) {
                try { if (boost != null) boost.release(); } catch (Throwable ignore) {}
                boost = null;
            }
        }
        try {
            loud = new LoudnessEnhancer(0);
            loud.setTargetGain(mode == BASS ? 650 : 300); // мБ: возвращает громкость, съеденную подъёмом низов
            loud.setEnabled(true);
            ok.append("Loudness ");
        } catch (Throwable t) {
            try { if (loud != null) loud.release(); } catch (Throwable ignore) {}
            loud = null;
        }
        return ok.toString().trim();
    }

    /** Информация о полосах для экрана эквалайзера: {число полос, min мБ, max мБ}; null если нет эквалайзера. */
    public static synchronized int[] bandInfo() {
        try {
            Equalizer e = new Equalizer(0, 0);
            short[] r = e.getBandLevelRange();
            int[] res = {e.getNumberOfBands(), r[0], r[1]};
            e.release();
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized int bandHz(int band) {
        try {
            Equalizer e = new Equalizer(0, 0);
            int hz = e.getCenterFreq((short) band) / 1000;
            e.release();
            return hz;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Живое изменение одной полосы (эквалайзер должен быть включён через apply). */
    public static synchronized void setBand(int band, short mb) {
        try { if (eq != null) eq.setBandLevel((short) band, mb); } catch (Throwable ignore) {}
    }

    private static void release() {
        try { if (boost != null) { boost.setEnabled(false); boost.release(); } } catch (Throwable ignore) {}
        try { if (eq != null) { eq.setEnabled(false); eq.release(); } } catch (Throwable ignore) {}
        try { if (loud != null) { loud.setEnabled(false); loud.release(); } } catch (Throwable ignore) {}
        boost = null; eq = null; loud = null;
    }
}
