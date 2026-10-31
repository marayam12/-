package com.renexamc.app

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.renexamc.lib.ErrorLog

/**
 * Real rewarded ads (Google AdMob).
 *
 * The site (assets/ad-viewer/ad-viewer.js) calls AndroidBridge.showRewardedAd(kind).
 * When the ad finishes, [onResult] is called with:
 *   "earned"    - the player watched the ad and earned the reward
 *   "closed"    - the player closed it before the end (no reward)
 *   "not_ready" - no ad was loaded yet (a new one is being loaded)
 *   "error"     - the ad could not be shown
 */
class AdsManager(
    private val activity: Activity,
    private val onResult: (kind: String, outcome: String) -> Unit
) {
    private var rewardedAd: RewardedAd? = null
    private var loading = false

    init {
        try {
            MobileAds.initialize(activity) { }
            load()
        } catch (e: Exception) {
            ErrorLog.add("AdMob init failed: ${e.message}")
        }
    }

    fun isReady(): Boolean = rewardedAd != null

    // The real unit, or Google's test unit when admob_use_test_ads is true (res/values/admob.xml).
    private fun unitId(): String =
        if (activity.resources.getBoolean(R.bool.admob_use_test_ads))
            activity.getString(R.string.admob_test_rewarded_unit_id)
        else
            activity.getString(R.string.admob_rewarded_unit_id)

    fun load() {
        if (loading || rewardedAd != null) return
        loading = true
        try {
            RewardedAd.load(
                activity,
                unitId(),
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        loading = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        rewardedAd = null
                        loading = false
                        ErrorLog.add("AdMob load failed: ${error.code} ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            loading = false
            ErrorLog.add("AdMob load exception: ${e.message}")
        }
    }

    fun show(kind: String) {
        val ad = rewardedAd
        if (ad == null) {
            load()
            onResult(kind, "not_ready")
            return
        }
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                load()
                onResult(kind, if (earned) "earned" else "closed")
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                load()
                ErrorLog.add("AdMob show failed: ${error.code} ${error.message}")
                onResult(kind, "error")
            }
        }
        try {
            ad.show(activity, OnUserEarnedRewardListener { earned = true })
        } catch (e: Exception) {
            rewardedAd = null
            ErrorLog.add("AdMob show exception: ${e.message}")
            onResult(kind, "error")
        }
    }
}
