package local.headunit.antiradar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * База камер: «osm» (скачивается из OpenStreetMap для Республики Башкортостан) и «user» (добавлены вручную).
 * Данные OpenStreetMap: © участники OpenStreetMap, лицензия ODbL.
 */
public final class CameraDb {
    private static final String OSM_FILE = "cameras_osm.json";
    private static final String USER_FILE = "cameras_user.json";
    private static final String EXT_FILE = "cameras_ext.json";
    private static final String[] ENDPOINTS = {
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",   // российское зеркало
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    // прямоугольник вокруг Республики Башкортостан (юг, запад, север, восток) + небольшой запас
    private static final double S = 49.8, W = 52.0, N = 58.0, E = 61.5;
    private static final String QUERY =
            "[out:json][timeout:60];(node[\"highway\"=\"speed_camera\"](" + S + "," + W + "," + N + "," + E + ");"
                    + "node[\"enforcement\"=\"maxspeed\"](" + S + "," + W + "," + N + "," + E + "););out body;";

    private CameraDb() {}

    public static List<Camera> load(Context c) {
        List<Camera> all = new ArrayList<>();
        readFile(new File(c.getFilesDir(), USER_FILE), true, all);
        readFile(new File(c.getFilesDir(), EXT_FILE), false, all);
        readFile(new File(c.getFilesDir(), OSM_FILE), false, all);
        // убираем дубли (камеры ближе 40 м друг к другу), ваши добавлены первыми и остаются
        List<Camera> r = new ArrayList<>();
        for (Camera c1 : all) {
            boolean dup = false;
            for (Camera c2 : r) {
                if (Math.abs(c1.lat - c2.lat) < 0.0004 && Math.abs(c1.lon - c2.lon) < 0.0007) { dup = true; break; }
            }
            if (!dup) r.add(c1);
        }
        return r;
    }

    public static int countExt(Context c) {
        List<Camera> r = new ArrayList<>();
        readFile(new File(c.getFilesDir(), EXT_FILE), false, r);
        return r.size();
    }

    /**
     * Разбор базы из файла популярных форматов (PocketGPS CSV, Navitel speedcam.txt, просто «долгота,широта,...»).
     * Берёт только камеры в районе Башкортостана. @return сколько камер сохранено
     */
    public static int importText(Context c, String text) throws Exception {
        java.util.regex.Pattern num = java.util.regex.Pattern.compile("-?\\d{1,3}\\.\\d{3,}");
        List<double[]> pairs = new ArrayList<>();   // {a, b, speed}
        int okAB = 0, okBA = 0;
        for (String line : text.split("\\r?\\n")) {
            java.util.regex.Matcher m = num.matcher(line);
            if (!m.find()) continue;
            double a = Double.parseDouble(m.group());
            if (!m.find()) continue;
            double b = Double.parseDouble(m.group());
            int end = m.end();
            int limit = 0;
            for (String fld : line.substring(end).split("[,;\\t]")) {
                String s = fld.trim().replace("\"", "");
                if (s.matches("\\d{2,3}")) {
                    int v = Integer.parseInt(s);
                    if (v >= 20 && v <= 130 && v % 10 == 0) { limit = v; break; }
                }
            }
            if (limit == 0) {
                java.util.regex.Matcher km = java.util.regex.Pattern.compile("(\\d{2,3})\\s*(км|km)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line.substring(end));
                if (km.find()) {
                    int v = Integer.parseInt(km.group(1));
                    if (v >= 20 && v <= 130) limit = v;
                }
            }
            if (inBox(b, a)) okAB++;      // порядок «долгота, широта»
            if (inBox(a, b)) okBA++;      // порядок «широта, долгота»
            pairs.add(new double[]{a, b, limit});
        }
        if (pairs.isEmpty()) throw new Exception("В файле не нашлось координат");
        boolean lonLat = okAB >= okBA;
        JSONArray out = new JSONArray();
        for (double[] p : pairs) {
            double lon = lonLat ? p[0] : p[1], lat = lonLat ? p[1] : p[0];
            if (!inBox(lat, lon)) continue;
            out.put(toJson(new Camera(lat, lon, (int) p[2], -1, false)));
        }
        if (out.length() == 0) throw new Exception("В файле нет камер Башкортостана (всего строк: " + pairs.size() + ")");
        writeFile(new File(c.getFilesDir(), EXT_FILE), out.toString());
        return out.length();
    }

    private static boolean inBox(double lat, double lon) {
        return lat >= S && lat <= N && lon >= W && lon <= E;
    }

    /** Скачивает файл базы по ссылке (ссылку выбирает пользователь) и импортирует. */
    public static int importFromUrl(Context c, String url) throws Exception {
        return importText(c, http(url));
    }

    public static int countUser(Context c) {
        List<Camera> r = new ArrayList<>();
        readFile(new File(c.getFilesDir(), USER_FILE), true, r);
        return r.size();
    }

    public static synchronized void addUser(Context c, Camera cam) {
        try {
            File f = new File(c.getFilesDir(), USER_FILE);
            JSONArray a = new JSONArray();
            List<Camera> old = new ArrayList<>();
            readFile(f, true, old);
            for (Camera o : old) a.put(toJson(o));
            a.put(toJson(cam));
            writeFile(f, a.toString());
        } catch (Exception ignore) { }
    }

    /** Скачивает камеры из OpenStreetMap и сохраняет. @return сколько камер получено */
    public static int updateFromOsm(Context c) throws Exception {
        Exception last = null;
        for (String ep : ENDPOINTS) {
            try {
                String body = http(ep + "?data=" + URLEncoder.encode(QUERY, "UTF-8"));
                JSONArray els = new JSONObject(body).getJSONArray("elements");
                JSONArray out = new JSONArray();
                for (int i = 0; i < els.length(); i++) {
                    JSONObject e = els.getJSONObject(i);
                    if (!e.has("lat") || !e.has("lon")) continue;
                    JSONObject tags = e.optJSONObject("tags");
                    int limit = 0, dir = -1;
                    if (tags != null) {
                        limit = parseInt(tags.optString("maxspeed", ""));
                        dir = parseDir(tags.optString("direction", tags.optString("camera:direction", "")));
                    }
                    out.put(toJson(new Camera(e.getDouble("lat"), e.getDouble("lon"), limit, dir, false)));
                }
                if (out.length() == 0) throw new Exception("Сервер вернул пустой список");
                writeFile(new File(c.getFilesDir(), OSM_FILE), out.toString());
                return out.length();
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new Exception("не удалось связаться ни с одним сервером OpenStreetMap (" + (last != null ? last.getMessage() : "нет связи") + ")");
    }

    private static int parseInt(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) { if (Character.isDigit(ch)) b.append(ch); else if (b.length() > 0) break; }
        try { return b.length() == 0 ? 0 : Integer.parseInt(b.toString()); } catch (Exception e) { return 0; }
    }

    private static int parseDir(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            double d = Double.parseDouble(s.trim().replace(',', '.'));
            return (int) (((d % 360) + 360) % 360);
        } catch (Exception ignore) { }
        switch (s.trim().toUpperCase()) {
            case "N": return 0;   case "NE": return 45;  case "E": return 90;  case "SE": return 135;
            case "S": return 180; case "SW": return 225; case "W": return 270; case "NW": return 315;
            default: return -1;
        }
    }

    private static JSONObject toJson(Camera c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("lat", c.lat); o.put("lon", c.lon); o.put("limit", c.limit); o.put("dir", c.dir);
        return o;
    }

    private static void readFile(File f, boolean user, List<Camera> out) {
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray a = new JSONArray(bo.toString("UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Camera(o.getDouble("lat"), o.getDouble("lon"), o.optInt("limit", 0), o.optInt("dir", -1), user));
            }
        } catch (Exception ignore) { }
    }

    private static void writeFile(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) throw new Exception("Не удалось сохранить файл");
    }

    private static String http(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "AntiRadarRB/1.0 (Android head unit)");
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally { h.disconnect(); }
    }
}
