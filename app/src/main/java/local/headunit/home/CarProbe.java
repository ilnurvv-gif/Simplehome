package local.headunit.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Разведка: что магнитола умеет отдавать из CAN (расход, обороты и т.п.). Только отчёт, ничего не меняет. */
final class CarProbe {
    private static final Pattern P = Pattern.compile(
            "can|car|syu|fyt|hzbhd|obd|vehicle|canbus|auto|txz|mcu|carsetting|bhd|dsp", Pattern.CASE_INSENSITIVE);
    // свойства Android Automotive (VehicleProperty)
    private static final int[][] PROPS = {
            {0x11600207, 'f'}, // PERF_VEHICLE_SPEED
            {0x11600305, 'f'}, // ENGINE_RPM
            {0x11600307, 'f'}, // FUEL_LEVEL
            {0x11600308, 'f'}, // RANGE_REMAINING
            {0x11600304, 'f'}, // ENGINE_COOLANT_TEMP
            {0x11600303, 'f'}, // ENGINE_OIL_TEMP
    };
    private static final String[] NAMES = {"скорость", "обороты", "уровень топлива", "запас хода", "темп. ОЖ", "темп. масла"};

    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n');
        PackageManager pm = c.getPackageManager();
        sb.append("automotive: ").append(pm.hasSystemFeature("android.hardware.type.automotive")).append('\n');
        sb.append("android.car: ").append(carApi(c)).append("\n\n");

        sb.append("Пакеты, похожие на CAN/авто:\n");
        int n = 0;
        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                String label = String.valueOf(pm.getApplicationLabel(ai));
                if (!P.matcher(ai.packageName).find() && !P.matcher(label).find()) continue;
                if (ai.packageName.startsWith("com.google.") || ai.packageName.startsWith("com.android.")
                        && !ai.packageName.contains("car")) continue;
                n++;
                if (n > 40) break;
                sb.append("• ").append(ai.packageName).append("  (").append(label).append(")\n");
                try {
                    PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_SERVICES);
                    if (pi.services != null) {
                        int k = 0;
                        for (ServiceInfo si : pi.services) {
                            if (!si.exported) continue;
                            if (k++ >= 4) break;
                            sb.append("     служба: ").append(si.name).append('\n');
                        }
                    }
                } catch (Throwable ignore) { }
            }
        } catch (Throwable t) { sb.append("список пакетов недоступен: ").append(t).append('\n'); }
        if (n == 0) sb.append("(не найдено)\n");
        return sb.toString();
    }

    /** Пытаемся прочитать свойства через android.car (если вообще есть в прошивке). */
    private static String carApi(Context c) {
        try {
            Class<?> car = Class.forName("android.car.Car");
            Method create = car.getMethod("createCar", Context.class);
            Object o = create.invoke(null, c);
            Method conn = car.getMethod("isConnected");
            Method get = car.getMethod("getCarManager", String.class);
            Object pmgr = get.invoke(o, "property");
            if (pmgr == null) return "есть, но CarPropertyManager нет";
            StringBuilder sb = new StringBuilder("есть\n");
            Method gf = pmgr.getClass().getMethod("getFloatProperty", int.class, int.class);
            for (int i = 0; i < PROPS.length; i++) {
                try {
                    Object v = gf.invoke(pmgr, PROPS[i][0], 0);
                    sb.append("   ").append(NAMES[i]).append(" = ").append(v).append('\n');
                } catch (Throwable t) {
                    sb.append("   ").append(NAMES[i]).append(": нет\n");
                }
            }
            return sb.toString().trim();
        } catch (ClassNotFoundException e) {
            return "нет (обычная прошивка)";
        } catch (Throwable t) {
            return "ошибка " + t.getClass().getSimpleName();
        }
    }
}
