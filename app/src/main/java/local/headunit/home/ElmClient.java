package local.headunit.home;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** ELM327 по Bluetooth: скорость, обороты, температура, напряжение, расход топлива, коды ошибок. */
final class ElmClient {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ---- данные для экрана ----
    volatile float speed = -1, rpm = -1, coolant = -999, volt = -1;
    volatile float lph = -1, lp100 = -1, avg = -1, tripKm = 0, tripL = 0;
    volatile long speedT;
    volatile boolean connected, mil;
    volatile int dtcCount;
    volatile String dtc = "";
    volatile String status = "ELM327: адаптер не выбран";
    volatile float engineL = 2.0f;           // объём двигателя, л (для расчёта расхода по MAP)

    private volatile boolean running, wantClear, wantDtc;
    private Thread th;
    private BluetoothSocket sock;
    private InputStream in;
    private OutputStream out;

    boolean fresh() { return connected && System.currentTimeMillis() - speedT < 3000 && speed >= 0; }

    void requestClear() { wantClear = true; }
    void requestDtc() { wantDtc = true; }

    synchronized void start(final String addr) {
        stop();
        if (addr == null || addr.isEmpty()) { status = "ELM327: адаптер не выбран"; return; }
        running = true;
        th = new Thread(() -> loop(addr), "elm");
        th.setDaemon(true);
        th.start();
    }

    synchronized void stop() {
        running = false;
        connected = false;
        closeQuiet();
        if (th != null) { th.interrupt(); th = null; }
    }

    // ---- основной цикл: подключиться, опрашивать, при обрыве — повторить ----
    private void loop(String addr) {
        while (running) {
            try {
                status = "ELM327: подключение…";
                connect(addr);
                init();
                status = "";
                connected = true;
                poll();
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                status = "ELM327: " + (t.getMessage() == null ? "нет связи" : t.getMessage());
            }
            connected = false;
            closeQuiet();
            if (!running) break;
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
        }
    }

    @SuppressLint("MissingPermission")
    private void connect(String addr) throws IOException {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) throw new IOException("Bluetooth выключен");
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        try { ad.cancelDiscovery(); } catch (Throwable ignore) { }
        try {
            sock = dev.createRfcommSocketToServiceRecord(SPP);
            sock.connect();
        } catch (IOException e) {
            closeQuiet();
            try {   // запасной способ для «китайских» адаптеров
                sock = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                sock.connect();
            } catch (Throwable t) {
                closeQuiet();
                throw new IOException("адаптер не отвечает (включено ли зажигание?)");
            }
        }
        in = sock.getInputStream();
        out = sock.getOutputStream();
    }

    private void closeQuiet() {
        try { if (sock != null) sock.close(); } catch (Throwable ignore) { }
        sock = null; in = null; out = null;
    }

    private String cmd(String c, int timeoutMs) throws IOException, InterruptedException {
        while (in.available() > 0) in.read();
        out.write((c + "\r").getBytes());
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) throw new IOException("связь потеряна");
                if (b == '>') break;
                sb.append((char) b);
            } else {
                Thread.sleep(8);
            }
        }
        return sb.toString();
    }

    private void init() throws IOException, InterruptedException {
        cmd("ATZ", 2500);
        cmd("ATE0", 800);
        cmd("ATL0", 800);
        cmd("ATS0", 800);
        cmd("ATH0", 800);
        cmd("ATSP0", 800);
        int[] r = q("00", 9000);     // здесь адаптер ищет протокол автомобиля
        if (r == null) throw new IOException("нет связи с авто (зажигание?)");
        loadSupport();
    }

    // ---- какие датчики есть у машины ----
    private final boolean[] sup = new boolean[0x80];
    private void loadSupport() throws IOException, InterruptedException {
        java.util.Arrays.fill(sup, false);
        for (int base = 0; base < 0x60; base += 0x20) {
            int[] m = q(String.format("%02X", base), 3000);
            if (m == null || m.length < 4) break;
            for (int i = 0; i < 32; i++) if ((m[i / 8] & (0x80 >> (i % 8))) != 0) sup[base + i + 1] = true;
            if (!sup[base + 0x20]) break;
        }
    }

    /** Запрос mode 01: возвращает байты данных или null. */
    private int[] q(String pid, int timeoutMs) throws IOException, InterruptedException {
        String r = cmd("01" + pid, timeoutMs);
        for (String line : r.split("[\r\n]+")) {
            String l = line.replace(" ", "").toUpperCase();
            int i = l.indexOf("41" + pid);
            if (i < 0) continue;
            String h = l.substring(i + 4);
            int n = 0;
            while (n < h.length() && Character.digit(h.charAt(n), 16) >= 0) n++;
            n -= n % 2;
            int[] d = new int[n / 2];
            for (int k = 0; k < d.length; k++) d[k] = Integer.parseInt(h.substring(2 * k, 2 * k + 2), 16);
            return d.length == 0 ? null : d;
        }
        return null;
    }

    private void poll() throws IOException, InterruptedException {
        long last = System.currentTimeMillis(), lastDtc = 0;
        int rot = 0, miss = 0;
        float lphS = -1, lpS = -1;
        while (running) {
            long now = System.currentTimeMillis();
            float dt = Math.min(2f, (now - last) / 1000f);
            last = now;
            boolean got = false;

            int[] v = q("0D", 800);
            if (v != null) { speed = v[0]; speedT = now; got = true; }
            v = q("0C", 800);
            if (v != null && v.length >= 2) { rpm = (v[0] * 256 + v[1]) / 4f; got = true; }

            float fuelLph = -1;
            if (sup[0x5E]) {
                v = q("5E", 800);
                if (v != null && v.length >= 2) fuelLph = (v[0] * 256 + v[1]) / 20f;
            }
            if (fuelLph < 0 && sup[0x10]) {
                v = q("10", 800);
                if (v != null && v.length >= 2) fuelLph = (v[0] * 256 + v[1]) / 100f * 0.3287f;   // MAF г/с → л/ч (бензин)
            }
            if (fuelLph < 0 && sup[0x0B] && sup[0x0F] && rpm >= 0) {                              // по давлению во впуске
                int[] map = q("0B", 800), iat = q("0F", 800);
                if (map != null && iat != null) {
                    float imap = rpm * map[0] / (iat[0] - 40 + 273.15f) / 2f;
                    float maf = imap / 60f * 0.85f * engineL * 28.97f / 8.314f;
                    fuelLph = maf * 0.3287f;
                }
            }
            if (fuelLph >= 0) {
                lphS = lphS < 0 ? fuelLph : lphS + (fuelLph - lphS) * 0.25f;
                lph = lphS;
                float sp = speed;
                if (sp > 3f) {
                    float inst = fuelLph / sp * 100f;
                    lpS = lpS < 0 ? inst : lpS + (inst - lpS) * 0.15f;
                    lp100 = Math.min(60f, lpS);
                } else { lp100 = -1; lpS = -1; }
                tripL += fuelLph * dt / 3600f;
            }
            if (speed > 0) tripKm += speed * dt / 3600f;
            if (tripKm > 0.3f) avg = tripL / tripKm * 100f;

            switch (rot++ % 3) {
                case 0:
                    if (sup[0x05]) { v = q("05", 800); if (v != null) coolant = v[0] - 40; }
                    break;
                case 1:
                    if (sup[0x42]) { v = q("42", 800); if (v != null && v.length >= 2) volt = (v[0] * 256 + v[1]) / 1000f; }
                    break;
                default: break;
            }

            if (wantClear) { wantClear = false; cmd("04", 3000); lastDtc = 0; }
            if (wantDtc || now - lastDtc > 30000) { wantDtc = false; lastDtc = now; readDtc(); }

            if (!got) { if (++miss > 12) throw new IOException("авто не отвечает"); } else miss = 0;
        }
    }

    // ---- коды ошибок (mode 03) ----
    private void readDtc() throws IOException, InterruptedException {
        int[] a = q("01", 1500);
        if (a != null && a.length >= 1) { mil = (a[0] & 0x80) != 0; dtcCount = a[0] & 0x7F; }
        if (dtcCount == 0) { dtc = ""; return; }
        String r = cmd("03", 3500);
        StringBuilder hex = new StringBuilder();
        for (String line : r.split("[\r\n]+")) {
            String l = line.replace(" ", "").toUpperCase().replaceFirst("^[0-9]:", "");
            if (l.length() > 3 && l.matches("[0-9A-F]+")) hex.append(l);
        }
        String h = hex.toString();
        int i = h.indexOf("43");
        if (i < 0) { dtc = ""; return; }
        h = h.substring(i + 2);
        if (h.length() % 4 == 2) h = h.substring(2);          // CAN: первый байт — число кодов
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int p = 0; p + 4 <= h.length() && n < 8; p += 4) {
            String pair = h.substring(p, p + 4);
            if (pair.equals("0000")) continue;
            int d = Character.digit(pair.charAt(0), 16);
            if (sb.length() > 0) sb.append(' ');
            sb.append("PCBU".charAt(d >> 2)).append(d & 3).append(pair, 1, 4);
            n++;
        }
        dtc = sb.toString();
    }
}
