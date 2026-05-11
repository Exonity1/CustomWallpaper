package com.exonity.wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(9, 14, 22));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.launcher_hint);
        subtitle.setTextColor(Color.rgb(187, 202, 220));
        subtitle.setTextSize(15f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(12), 0, dp(24));

        Button button = new Button(this);
        button.setText(R.string.open_wallpaper_picker);
        button.setAllCaps(false);
        button.setOnClickListener(view -> openWallpaperPicker());

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
    }

    private void openWallpaperPicker() {
        ComponentName componentName = new ComponentName(this, EfficientWallpaperService.class);
        Intent directIntent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        directIntent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName);

        try {
            startActivity(directIntent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
