package com.capture.clipper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Quality Presets");
        title.setTextSize(18);
        root.addView(title);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);

        RadioButton r1 = new RadioButton(this);
        r1.setText("720p @ 60fps (recommended)");
        r1.setTag(0);
        RadioButton r2 = new RadioButton(this);
        r2.setText("720p @ 30fps");
        r2.setTag(1);
        RadioButton r3 = new RadioButton(this);
        r3.setText("480p @ 30fps");
        r3.setTag(2);

        rg.addView(r1);
        rg.addView(r2);
        rg.addView(r3);

        int sel = prefs.getInt("quality_index", 0);
        if (sel == 0) r1.setChecked(true);
        else if (sel == 1) r2.setChecked(true);
        else r3.setChecked(true);

        root.addView(rg);

        TextView bt = new TextView(this);
        bt.setText("Bitrate presets (kbps)");
        bt.setTextSize(18);
        bt.setPadding(0, 24, 0, 8);
        root.addView(bt);

        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);

        Button bLow = new Button(this);
        bLow.setText("1500");
        Button bMed = new Button(this);
        bMed.setText("4000");
        Button bHigh = new Button(this);
        bHigh.setText("8000");

        bitrateRow.addView(bLow);
        bitrateRow.addView(bMed);
        bitrateRow.addView(bHigh);
        root.addView(bitrateRow);

        TextView hint = new TextView(this);
        hint.setText("Tap a preset to apply it, or use the in-app quick settings to input a custom value.");
        hint.setTextSize(12);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, 8, 0, 8);
        root.addView(hint);

        Button save = new Button(this);
        save.setText("Save Settings");
        save.setOnClickListener(v -> {
            int idx = 0;
            if (r2.isChecked()) idx = 1;
            else if (r3.isChecked()) idx = 2;
            prefs.edit().putInt("quality_index", idx).apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        root.addView(save);

        sv.addView(root);
        setContentView(sv);

        bLow.setOnClickListener(v -> prefs.edit().putInt("bitrate_kbps", 1500).apply());
        bMed.setOnClickListener(v -> prefs.edit().putInt("bitrate_kbps", 4000).apply());
        bHigh.setOnClickListener(v -> prefs.edit().putInt("bitrate_kbps", 8000).apply());
    }
}
