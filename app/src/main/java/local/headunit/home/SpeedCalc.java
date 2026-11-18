package local.headunit.home;

/**
 * Скорость для показа на экране. GPS обычно показывает меньше спидометра и отстаёт на ~1 с, поэтому:
 * 1) смотрим на разгон/торможение и забегаем вперёд на ~0,8 с (убирает отставание);
 * 2) прибавляем 4 км/ч (как у штатного спидометра);
 * 3) чуть сглаживаем, чтобы цифра не дёргалась; на стоянке показываем 0.
 */
final class SpeedCalc {
    static final float OFFSET_KMH = 4f;
    private float prevRaw = -1f, shown = 0f;
    private long prevT;

    float update(float rawKmh, long tMs) {
        float acc = 0f; // км/ч в секунду
        if (prevRaw >= 0f) {
            float dt = (tMs - prevT) / 1000f;
            if (dt > 0.3f && dt < 3f) acc = (rawKmh - prevRaw) / dt;
            else if (dt >= 3f) shown = 0f; // после паузы сглаживание начинаем заново
        }
        prevRaw = rawKmh;
        prevT = tMs;
        if (rawKmh < 3f) { shown = 0f; return 0f; }
        float lead = Math.max(-4f, Math.min(4f, acc * 0.8f));
        float target = rawKmh + lead + OFFSET_KMH;
        shown = shown == 0f ? target : shown + (target - shown) * 0.7f;
        return Math.max(0f, shown);
    }

    void reset() { prevRaw = -1f; shown = 0f; }
}
