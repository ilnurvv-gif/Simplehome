package local.headunit.home;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Окно эквалайзера: ползунок на каждую полосу, изменения слышны сразу. */
public final class EqDialog {
    public interface Saved { void onSaved(short[] levelsMb); }

    private EqDialog() {}

    public static void show(Context c, short[] start, Saved cb) {
        final int[] info = BassController.bandInfo();
        if (info == null) {
            new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Эквалайзер недоступен на этой прошивке")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final int n = info[0], min = info[1], max = info[2];
        final int steps = (max - min) / 100; // шаг 1 дБ
        final short[] lv = new short[n];
        for (int i = 0; i < n; i++) lv[i] = (start != null && i < start.length) ? start[i] : 0;
        BassController.apply(BassController.CUSTOM, lv);

        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (6 * d));
        final SeekBar[] bars = new SeekBar[n];
        final TextView[] labels = new TextView[n];
        for (int i = 0; i < n; i++) {
            final int band = i;
            int hz = BassController.bandHz(i);
            final String name = hz >= 1000 ? (hz / 1000f) + " кГц" : hz + " Гц";
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView hzv = new TextView(c);
            hzv.setText(name);
            hzv.setTextColor(0xFFFFFFFF);
            hzv.setMinWidth((int) (90 * d));
            final TextView val = new TextView(c);
            val.setTextColor(0xFFCFE2FF);
            val.setMinWidth((int) (70 * d));
            val.setGravity(Gravity.END);
            SeekBar sb = new SeekBar(c);
            sb.setMax(steps);
            sb.setProgress(Math.max(0, Math.min(steps, (lv[i] - min) / 100)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    short mb = (short) (min + p * 100);
                    lv[band] = mb;
                    val.setText((mb >= 0 ? "+" : "") + (mb / 100) + " дБ");
                    if (user) BassController.setBand(band, mb);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            val.setText(((min + sb.getProgress() * 100) >= 0 ? "+" : "") + ((min + sb.getProgress() * 100) / 100) + " дБ");
            bars[i] = sb; labels[i] = val;
            row.addView(hzv);
            row.addView(sb, lp);
            row.addView(val);
            root.addView(row);
        }
        AlertDialog dlg = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Эквалайзер")
                .setView(root)
                .setPositiveButton("Готово", (dd, w) -> { if (cb != null) cb.onSaved(lv); })
                .setNeutralButton("Сброс", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            for (int i = 0; i < n; i++) {
                bars[i].setProgress((0 - min) / 100);
                BassController.setBand(i, (short) 0);
                lv[i] = 0;
            }
        });
    }
}
