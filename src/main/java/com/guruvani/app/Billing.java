package com.guruvani.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * Google Play subscription: one monthly plan with a 7-day free trial.
 *
 * In Play Console the subscription must be created with this exact product ID.
 * The price, the trial and who is eligible for it all come from Play Console,
 * not from this code: the app simply offers whichever offer Play returns.
 */
final class Billing implements PurchasesUpdatedListener {

    static final String PRODUCT_ID = "gurbani_monthly";

    interface Listener {
        void onChanged();
    }

    private static final String TAG = "GurbaniBilling";
    /** If Play can't be reached (offline), a subscriber stays unlocked for this long. */
    private static final long GRACE_MS = 3L * 24 * 60 * 60 * 1000;

    private final Activity activity;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean debuggable;

    private BillingClient client;
    private boolean connected;
    private boolean subscribed;
    private ProductDetails details;
    private String offerToken;
    private String price = "";
    private boolean trial;

    Billing(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.prefs = activity.getSharedPreferences("gurbani_billing", Context.MODE_PRIVATE);
        this.debuggable = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    void start() {
        try {
            client = BillingClient.newBuilder(activity)
                    .setListener(this)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .enableAutoServiceReconnection()
                    .build();
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(final BillingResult r) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            connected = r.getResponseCode() == BillingClient.BillingResponseCode.OK;
                            if (connected) {
                                refresh();
                                queryProduct();
                            } else {
                                Log.w(TAG, "billing unavailable: " + r.getResponseCode() + " " + r.getDebugMessage());
                            }
                            listener.onChanged();
                        }
                    });
                }

                @Override
                public void onBillingServiceDisconnected() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            connected = false;
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "billing start failed", t);
        }
    }

    void end() {
        try {
            if (client != null) client.endConnection();
        } catch (Throwable ignored) {
        }
    }

    /** Debug builds (emulator check, development) are always unlocked. */
    boolean isSubscribed() {
        return debuggable || subscribed || System.currentTimeMillis() < prefs.getLong("grace_until", 0);
    }

    String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("available", connected && details != null && offerToken != null);
            o.put("subscribed", isSubscribed());
            o.put("price", price);
            o.put("trial", trial);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Ask Play what this Google account currently owns. */
    void refresh() {
        if (client == null || !connected) return;
        try {
            client.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                    new PurchasesResponseListener() {
                        @Override
                        public void onQueryPurchasesResponse(final BillingResult r, final List<Purchase> list) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                                    apply(list, true);
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
    }

    /** Opens Google Play's purchase sheet. Returns false if Play has not given us the plan yet. */
    boolean subscribe() {
        if (client == null || !connected || details == null || offerToken == null) {
            if (client != null && connected) queryProduct();
            return false;
        }
        try {
            BillingFlowParams.ProductDetailsParams p = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offerToken)
                    .build();
            BillingFlowParams params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(p))
                    .build();
            BillingResult r = client.launchBillingFlow(activity, params);
            return r.getResponseCode() == BillingClient.BillingResponseCode.OK;
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
            return false;
        }
    }

    @Override
    public void onPurchasesUpdated(final BillingResult r, final List<Purchase> purchases) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                    apply(purchases, false);
                } else {
                    refresh();
                }
            }
        });
    }

    // ------------------------------------------------------------------

    /**
     * @param complete true when the list is everything the account owns (so "not in the list"
     *                 means "not subscribed"); false for a purchase-update, which only adds.
     */
    private void apply(List<Purchase> list, boolean complete) {
        boolean active = false;
        if (list != null) {
            for (Purchase p : list) {
                if (p == null || !p.getProducts().contains(PRODUCT_ID)) continue;
                if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                active = true;
                if (!p.isAcknowledged()) acknowledge(p);
            }
        }
        if (active) {
            subscribed = true;
            prefs.edit().putLong("grace_until", System.currentTimeMillis() + GRACE_MS).apply();
        } else if (complete) {
            subscribed = false;
            prefs.edit().remove("grace_until").apply();
        }
        listener.onChanged();
    }

    private void acknowledge(Purchase p) {
        try {
            client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                    new AcknowledgePurchaseResponseListener() {
                        @Override
                        public void onAcknowledgePurchaseResponse(BillingResult r) {
                            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                                Log.w(TAG, "acknowledge failed: " + r.getDebugMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "acknowledge failed", t);
        }
    }

    private void queryProduct() {
        try {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build();
            client.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder()
                            .setProductList(Collections.singletonList(product))
                            .build(),
                    new ProductDetailsResponseListener() {
                        @Override
                        public void onProductDetailsResponse(final BillingResult r, final QueryProductDetailsResult result) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                                        Log.w(TAG, "product query failed: " + r.getDebugMessage());
                                        return;
                                    }
                                    List<ProductDetails> list = result.getProductDetailsList();
                                    if (list == null || list.isEmpty()) {
                                        Log.w(TAG, "product " + PRODUCT_ID + " not found in Play Console");
                                        return;
                                    }
                                    pickOffer(list.get(0));
                                    listener.onChanged();
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "product query failed", t);
        }
    }

    /** Prefer an offer that starts with a free phase (the trial); otherwise the plain monthly plan. */
    private void pickOffer(ProductDetails d) {
        List<ProductDetails.SubscriptionOfferDetails> offers = d.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) return;
        ProductDetails.SubscriptionOfferDetails chosen = null;
        boolean chosenTrial = false;
        for (ProductDetails.SubscriptionOfferDetails o : offers) {
            List<ProductDetails.PricingPhase> phases = o.getPricingPhases().getPricingPhaseList();
            boolean free = !phases.isEmpty() && phases.get(0).getPriceAmountMicros() == 0;
            if (chosen == null || (free && !chosenTrial)) {
                chosen = o;
                chosenTrial = free;
            }
        }
        if (chosen == null) return;
        List<ProductDetails.PricingPhase> phases = chosen.getPricingPhases().getPricingPhaseList();
        details = d;
        offerToken = chosen.getOfferToken();
        trial = chosenTrial;
        price = phases.isEmpty() ? "" : phases.get(phases.size() - 1).getFormattedPrice();
    }
}
