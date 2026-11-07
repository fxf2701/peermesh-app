package app.peermesh

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

object PeerMeshBillingManager : PurchasesUpdatedListener {
    private const val TAG = "PeerMeshBilling"

    @Volatile
    private var billingClient: BillingClient? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var productDetails: ProductDetails? = null

    @Volatile
    private var lastRefreshRequestedAtMs: Long = 0L

    private val pendingReadyCallbacks = mutableListOf<BillingReadyCallback>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureConnected(context.applicationContext) {
            queryPurchases(context.applicationContext)
            queryProductDetails()
        }.onUnavailable {
            PeerMeshEntitlements.markBillingUnknown(context.applicationContext)
        }
    }

    fun refreshEntitlements(
        context: Context,
        onComplete: ((EntitlementState) -> Unit)? = null,
    ) {
        ensureConnected(context.applicationContext) {
            queryPurchases(context.applicationContext, onComplete)
        }.onUnavailable {
            PeerMeshEntitlements.markBillingUnknown(context.applicationContext)
            onComplete?.invoke(PeerMeshEntitlements.current())
        }
    }

    fun refreshEntitlementsIfStale(
        context: Context,
        minIntervalMs: Long = 60_000L,
        onComplete: ((EntitlementState) -> Unit)? = null,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshRequestedAtMs < minIntervalMs) {
            onComplete?.invoke(PeerMeshEntitlements.current())
            return
        }
        lastRefreshRequestedAtMs = now
        refreshEntitlements(context, onComplete)
    }

    fun launchProPurchase(activity: Activity) {
        val context = activity.applicationContext
        ensureConnected(context) {
            val details = productDetails
            if (details == null) {
                queryProductDetails { queriedDetails ->
                    if (queriedDetails != null) {
                        launchProPurchase(activity)
                    } else {
                        Toast.makeText(activity, R.string.pro_unlock_product_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
                return@ensureConnected
            }
            val offerToken = details.subscriptionOfferDetails
                ?.firstOrNull()
                ?.offerToken
            if (offerToken.isNullOrBlank()) {
                Toast.makeText(activity, R.string.pro_unlock_product_unavailable, Toast.LENGTH_SHORT).show()
                return@ensureConnected
            }
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
            val result = billingClient?.launchBillingFlow(activity, params)
            if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
                Toast.makeText(activity, R.string.pro_unlock_billing_unavailable, Toast.LENGTH_SHORT).show()
            }
        }.onUnavailable {
            Toast.makeText(activity, R.string.pro_unlock_billing_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val context = appContext ?: return
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                processPurchases(context, purchases.orEmpty(), fromQuery = false)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> {
                Log.w(TAG, "purchase update failed code=${billingResult.responseCode} ${billingResult.debugMessage}")
                refreshEntitlements(context)
            }
        }
    }

    private fun ensureConnected(
        context: Context,
        onReady: () -> Unit,
    ): BillingConnectionRequest {
        val request = BillingConnectionRequest()
        val client = billingClient ?: createClient(context.applicationContext).also { billingClient = it }
        if (client.isReady) {
            onReady()
            return request
        }
        synchronized(pendingReadyCallbacks) {
            pendingReadyCallbacks.add(BillingReadyCallback(onReady, request::notifyUnavailable))
            if (connecting) return request
            connecting = true
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val callbacks = synchronized(pendingReadyCallbacks) {
                    connecting = false
                    val readyCallbacks = pendingReadyCallbacks.toList()
                    pendingReadyCallbacks.clear()
                    readyCallbacks
                }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    callbacks.forEach { it.onReady.invoke() }
                } else {
                    Log.w(TAG, "billing setup failed code=${result.responseCode} ${result.debugMessage}")
                    callbacks.forEach { it.onUnavailable.invoke() }
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
            }
        })
        return request
    }

    private fun createClient(context: Context): BillingClient {
        val pendingPurchases = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .enablePrepaidPlans()
            .build()
        return BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(pendingPurchases)
            .enableAutoServiceReconnection()
            .build()
    }

    private fun queryProductDetails(onDone: ((ProductDetails?) -> Unit)? = null) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BuildConfig.PEER_MESH_PRO_SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        billingClient?.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = productDetailsResult.productDetailsList
                    .firstOrNull { it.productId == BuildConfig.PEER_MESH_PRO_SUBSCRIPTION_ID }
            } else {
                Log.w(TAG, "product query failed code=${result.responseCode} ${result.debugMessage}")
            }
            onDone?.invoke(productDetails)
        }
    }

    private fun queryPurchases(
        context: Context,
        onComplete: ((EntitlementState) -> Unit)? = null,
    ) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(context, purchases, fromQuery = true)
            } else {
                Log.w(TAG, "purchase query failed code=${result.responseCode} ${result.debugMessage}")
                PeerMeshEntitlements.markBillingUnknown(context)
            }
            onComplete?.invoke(PeerMeshEntitlements.current())
        }
    }

    private fun processPurchases(context: Context, purchases: List<Purchase>, fromQuery: Boolean) {
        val activePurchase = purchases.firstOrNull { purchase ->
            purchase.products.contains(BuildConfig.PEER_MESH_PRO_SUBSCRIPTION_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (activePurchase != null) {
            PeerMeshEntitlements.updateFromBilling(
                context = context,
                active = true,
                trial = false,
            )
            acknowledgeIfNeeded(activePurchase)
            return
        }
        val hasPending = purchases.any { purchase ->
            purchase.products.contains(BuildConfig.PEER_MESH_PRO_SUBSCRIPTION_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PENDING
        }
        if (!hasPending && fromQuery) {
            PeerMeshEntitlements.updateFromBilling(
                context = context,
                active = false,
                trial = false,
            )
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "ack failed code=${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private data class BillingReadyCallback(
        val onReady: () -> Unit,
        val onUnavailable: () -> Unit,
    )

    private class BillingConnectionRequest {
        private var onUnavailable: (() -> Unit)? = null

        fun onUnavailable(callback: () -> Unit) {
            onUnavailable = callback
        }

        fun notifyUnavailable() {
            onUnavailable?.invoke()
        }
    }
}
