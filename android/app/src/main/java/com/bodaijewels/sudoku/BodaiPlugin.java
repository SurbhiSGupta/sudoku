package com.bodaijewels.sudoku;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;

// Small native helpers the game calls from index.html:
// a bug report email with a screenshot, and focus mode (hides the phone's bars)
@CapacitorPlugin(name = "Bodai")
public class BodaiPlugin extends Plugin {

    // Phone details for bug reports. fontScale matters: large text broke the board once.
    @PluginMethod
    public void deviceInfo(PluginCall call) {
        JSObject info = new JSObject();
        info.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        info.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        info.put("fontScale", getContext().getResources().getConfiguration().fontScale);
        try {
            info.put(
                "appVersion",
                getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName
            );
        } catch (Exception e) {
            info.put("appVersion", "?");
        }
        call.resolve(info);
    }

    // Takes a screenshot of the game and opens an email to Bodai with it attached
    @PluginMethod
    public void reportBug(PluginCall call) {
        String to = call.getString("to", "");
        String subject = call.getString("subject", "Bodai Sudoku bug");
        String body = call.getString("body", "");
        getActivity().runOnUiThread(() ->
            capture(bitmap -> {
                Uri shot = bitmap == null ? null : saveShot(bitmap);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_EMAIL, new String[] { to });
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.putExtra(Intent.EXTRA_TEXT, body);
                if (shot != null) {
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, shot);
                    send.setClipData(ClipData.newRawUri("screenshot", shot));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    send.setType("message/rfc822");
                }
                try {
                    getActivity().startActivity(Intent.createChooser(send, "Send bug report"));
                    call.resolve();
                } catch (Exception e) {
                    call.reject("No email app", e);
                }
            })
        );
    }

    interface ShotReady {
        void done(Bitmap bitmap);
    }

    // PixelCopy reads what's really on screen; older phones draw the view instead
    private void capture(ShotReady ready) {
        Window window = getActivity().getWindow();
        View root = window.getDecorView();
        int width = root.getWidth();
        int height = root.getHeight();
        if (width == 0 || height == 0) {
            ready.done(null);
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(
                window,
                bitmap,
                result -> ready.done(result == PixelCopy.SUCCESS ? bitmap : null),
                new Handler(Looper.getMainLooper())
            );
        } else {
            root.draw(new Canvas(bitmap));
            ready.done(bitmap);
        }
    }

    private Uri saveShot(Bitmap bitmap) {
        try {
            File file = new File(getContext().getCacheDir(), "bodai-bug.png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
        } catch (Exception e) {
            return null;
        }
    }

    // Focus mode: hide the status and navigation bars; a swipe from the edge
    // shows them for a moment
    @PluginMethod
    public void setFocus(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(() -> {
            Window window = getActivity().getWindow();
            WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (on) bars.hide(WindowInsetsCompat.Type.systemBars());
            else bars.show(WindowInsetsCompat.Type.systemBars());
            call.resolve();
        });
    }
}
