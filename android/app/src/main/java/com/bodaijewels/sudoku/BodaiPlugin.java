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
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;

// Small native helpers the game calls from index.html: a bug report email with
// a screenshot, focus mode (hides the phone's bars), the Support Bodai purchase
// and the home-screen widget
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

    // ---------- Support Bodai: a one-time purchase through Google Play ----------
    // One connection to Play, opened on first use. buy() waits for Play's answer
    // in onPurchasesUpdated; restore() asks Play what this account already owns.
    private BillingClient billing;
    private PluginCall buyCall;

    private void withBilling(PluginCall call, Runnable ready) {
        if (billing == null) {
            billing = BillingClient
                .newBuilder(getContext())
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
        }
        if (billing.isReady()) {
            ready.run();
            return;
        }
        billing.startConnection(
            new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult result) {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) ready.run();
                    else call.reject("Google Play isn't available: " + result.getDebugMessage());
                }

                @Override
                public void onBillingServiceDisconnected() {}
            }
        );
    }

    private void productDetails(PluginCall call, String productId, ProductReady ready) {
        QueryProductDetailsParams params = QueryProductDetailsParams
            .newBuilder()
            .setProductList(
                Collections.singletonList(
                    QueryProductDetailsParams.Product
                        .newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build();
        billing.queryProductDetailsAsync(
            params,
            (result, details) -> {
                List<ProductDetails> list = details.getProductDetailsList();
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || list.isEmpty()) {
                    call.reject("Product not found: " + productId);
                    return;
                }
                ready.done(list.get(0));
            }
        );
    }

    interface ProductReady {
        void done(ProductDetails product);
    }

    // The price in the player's own currency, e.g. "$2.99" or "₹250.00"
    @PluginMethod
    public void billingPrice(PluginCall call) {
        String productId = call.getString("productId", "");
        withBilling(call, () ->
            productDetails(call, productId, product -> {
                JSObject out = new JSObject();
                ProductDetails.OneTimePurchaseOfferDetails offer = product.getOneTimePurchaseOfferDetails();
                out.put("price", offer == null ? "" : offer.getFormattedPrice());
                call.resolve(out);
            })
        );
    }

    @PluginMethod
    public void buy(PluginCall call) {
        String productId = call.getString("productId", "");
        withBilling(call, () ->
            productDetails(call, productId, product -> {
                BillingFlowParams params = BillingFlowParams
                    .newBuilder()
                    .setProductDetailsParamsList(
                        Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()
                        )
                    )
                    .build();
                getActivity().runOnUiThread(() -> {
                    buyCall = call;
                    BillingResult result = billing.launchBillingFlow(getActivity(), params);
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        buyCall = null;
                        if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                            call.resolve(state("purchased"));
                        } else {
                            call.reject("Couldn't start the purchase: " + result.getDebugMessage());
                        }
                    }
                });
            })
        );
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        PluginCall call = buyCall;
        buyCall = null;
        int code = result.getResponseCode();
        String outcome = "failed";
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            outcome = "canceled";
            for (Purchase purchase : purchases) {
                if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    acknowledge(purchase);
                    outcome = "purchased";
                } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    outcome = "pending";
                }
            }
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            outcome = "canceled";
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            outcome = "purchased";
        }
        if (call != null) call.resolve(state(outcome));
        else if ("purchased".equals(outcome)) notifyListeners("purchased", state(outcome));
    }

    // Play refunds a purchase after three days unless the app acknowledges it
    private void acknowledge(Purchase purchase) {
        if (purchase.isAcknowledged()) return;
        billing.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build(),
            result -> {}
        );
    }

    // What this Google account owns, so a new phone or a reinstall gets the pack back
    @PluginMethod
    public void restore(PluginCall call) {
        String productId = call.getString("productId", "");
        withBilling(call, () ->
            billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        call.reject("Couldn't check purchases: " + result.getDebugMessage());
                        return;
                    }
                    String outcome = "none";
                    for (Purchase purchase : purchases) {
                        if (!purchase.getProducts().contains(productId)) continue;
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            acknowledge(purchase);
                            outcome = "purchased";
                        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                            outcome = "pending";
                        }
                    }
                    call.resolve(state(outcome));
                }
            )
        );
    }

    private JSObject state(String outcome) {
        JSObject out = new JSObject();
        out.put("state", outcome);
        return out;
    }

    // ---------- Home-screen widget (DailyWidget.java) ----------
    // The game tells the widget how today is going: solved or not, the clock, the streak
    @PluginMethod
    public void updateWidget(PluginCall call) {
        getContext()
            .getSharedPreferences(DailyWidget.PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("date", call.getString("date", ""))
            .putBoolean("solved", Boolean.TRUE.equals(call.getBoolean("solved", false)))
            .putInt("seconds", call.getInt("seconds", 0))
            .putInt("timer", call.getInt("timer", 0))
            .putInt("streak", call.getInt("streak", 0))
            .apply();
        DailyWidget.refresh(getContext());
        call.resolve();
    }

    // Was the app opened from the widget? Asked once at startup; later taps
    // arrive as an "openDaily" event
    @PluginMethod
    public void launchAction(PluginCall call) {
        Intent intent = getActivity().getIntent();
        boolean daily = intent != null && DailyWidget.OPEN_DAILY.equals(intent.getAction());
        if (daily) intent.setAction(Intent.ACTION_MAIN);
        JSObject out = new JSObject();
        out.put("openDaily", daily);
        call.resolve(out);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (DailyWidget.OPEN_DAILY.equals(intent.getAction())) notifyListeners("openDaily", new JSObject());
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
