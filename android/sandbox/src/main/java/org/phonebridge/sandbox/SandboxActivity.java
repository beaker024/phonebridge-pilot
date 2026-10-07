package org.phonebridge.sandbox;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

/** No accounts, network, permissions, purchases or persistent user data. */
public final class SandboxActivity extends Activity {
    private int count = 0;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 120, 24, 24);
        TextView title = new TextView(this);
        title.setText("Harmless phone control practice — never enter real secrets here");
        body.addView(title);
        TextView value = new TextView(this);
        value.setId(R.id.counter_value);
        value.setText("Tap count: 0");
        body.addView(value);
        Button button = new Button(this);
        button.setId(R.id.counter_button);
        button.setText("Increment harmless counter");
        button.setOnClickListener(v -> value.setText("Tap count: " + (++count)));
        body.addView(button);
        EditText field = new EditText(this);
        field.setId(R.id.practice_field);
        field.setHint("Practice text — use hello phone");
        field.setSingleLine(true);
        body.addView(field);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.practice_scroll);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 1; i <= 40; i++) {
            TextView row = new TextView(this);
            row.setText("Harmless row " + i);
            row.setPadding(8, 24, 8, 24);
            rows.addView(row);
        }
        scroll.addView(rows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        // Detect actual touchscreen contact in our cooperative test app, without
        // Accessibility touch exploration or intercepting another app's input.
        body.setAccessibilityDelegate(new View.AccessibilityDelegate());
        setContentView(body);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Accessibility gestures also arrive here; conservative invalidation
            // of both human and agent touches is intentional. No coordinates/text.
            getWindow().getDecorView().sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
        return super.dispatchTouchEvent(event);
    }
}
