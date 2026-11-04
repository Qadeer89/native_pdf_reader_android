package com.pdfreader.app.ads

import android.content.Context
import android.util.DisplayMetrics
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.flow.StateFlow

/**
 * DEC-004 (`docs/DECISIONS_AND_OPEN_QUESTIONS.md`): a single adaptive banner ad docked at
 * the bottom of the reader screen — the only ad placement approved for this app. Never
 * loads an ad until [canRequestAds] reports true (see [ConsentManager]); renders nothing
 * (and so never calls `MobileAds.initialize`/requests an ad) before that.
 */
@Composable
fun BannerAdView(canRequestAds: StateFlow<Boolean>, modifier: Modifier = Modifier) {
    val allowed by canRequestAds.collectAsState()
    if (!allowed) return

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            MobileAds.initialize(ctx)
            AdView(ctx).apply {
                setAdSize(adaptiveBannerSize(ctx))
                adUnitId = BANNER_AD_UNIT_ID
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

private fun adaptiveBannerSize(context: Context): AdSize {
    val displayMetrics: DisplayMetrics = context.resources.displayMetrics
    val adWidthPixels = displayMetrics.widthPixels.toFloat()
    val density = displayMetrics.density
    val adWidth = (adWidthPixels / density).toInt()
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
}
