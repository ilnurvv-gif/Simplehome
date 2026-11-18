package local.headunit.antiradar;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/** Голосовые подсказки: русский TTS + запасной сигнал, если голоса нет. */
final class Voice {
    private static TextToSpeech tts;
    private static volatile boolean ready;
    private static volatile String status = "голос: запускается…";
    private static String pending;
    private static AudioManager am;
    private static AudioFocusRequest focusReq;

    static String status() { return status; }
    static boolean isReady() { return ready; }

    static synchronized void init(Context c) {
        if (tts != null) return;
        final Context app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            tts = new TextToSpeech(app, st -> {
                if (st != TextToSpeech.SUCCESS) { status = "голос: движок речи не запустился"; return; }
                int r = tts.setLanguage(new Locale("ru", "RU"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(new Locale("ru"));
                }
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    status = "голос: нет русского голоса в движке речи (только сигнал)";
                    return;
                }
                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    }
                    tts.setSpeechRate(1.0f);
                } catch (Throwable ignore) { }
                ready = true;
                status = "голос: готов";
                String p; synchronized (Voice.class) { p = pending; pending = null; }
                if (p != null) say(p);
            });
        } catch (Throwable t) {
            status = "голос: недоступен";
        }
    }

    static void speak(String text) {
        if (ready) say(text);
        else synchronized (Voice.class) { pending = text; }
    }

    private static void say(String text) {
        try {
            focus();
            Bundle0.speak(tts, text);
        } catch (Throwable ignore) { }
    }

    private static void focus() {
        try {
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build();
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Throwable ignore) { }
    }

    static synchronized void shutdown() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignore) { }
        tts = null; ready = false; status = "голос: запускается…";
    }

    /** speak с учётом API 21. */
    private static final class Bundle0 {
        static void speak(TextToSpeech t, String s) {
            if (Build.VERSION.SDK_INT >= 21) t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "cam");
            else t.speak(s, TextToSpeech.QUEUE_FLUSH, null);
        }
    }
}
