package com.capture.clipper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Simple programmatic layout so no extra XML is required
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);

        EditText bitrate = new EditText(this);
        bitrate.setHint("Bitrate (kbps)");

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        bitrate.setText(String.valueOf(prefs.getInt("bitrate_kbps", 6000)));

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            int b = 6000;
            try { b = Integer.parseInt(bitrate.getText().toString().trim()); } catch (Exception ignored) {}
            prefs.edit().putInt("bitrate_kbps", b).apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        ll.addView(bitrate);
        ll.addView(save);
        setContentView(ll);
    }
}
