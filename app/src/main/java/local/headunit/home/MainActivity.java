package local.headunit.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Очень простой лаунчер: статичный фон, часы, сетка приложений.
 * Короткое нажатие - запуск, долгое - окно "О приложении"
 * (там можно отключить или остановить приложение).
 */
public class MainActivity extends Activity {

    private static class AppItem {
        String label;
        String pkg;
        Drawable icon;
    }

    private final List<AppItem> apps = new ArrayList<>();
    private GridView grid;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.grid);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = apps.get(position);
            Intent launch = getPackageManager().getLaunchIntentForPackage(item.pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        });

        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + apps.get(position).pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAppsAsync();
    }

    @Override
    public void onBackPressed() {
        // На главном экране "назад" ничего не делает
    }

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
