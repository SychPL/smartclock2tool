package pl.mateusz.clockadbprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        final String report = Report.get().render(appVersion());
        TextView tv = findViewById(R.id.reportText);
        tv.setText(report);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        Button copy = findViewById(R.id.btnCopy);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Smart Clock 2 Tools Report", report));
                    Toast.makeText(ReportActivity.this, "Report copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    File f = new File(dir, "clock_adb_probe_report_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".txt");
                    FileOutputStream os = new FileOutputStream(f);
                    os.write(report.getBytes("UTF-8"));
                    os.close();
                    Toast.makeText(ReportActivity.this, "Saved: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Report.get().exception("ReportActivity.save", t);
                    Toast.makeText(ReportActivity.this, "Save failed: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String appVersion() {
        try {
            return getPackageName() + " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
