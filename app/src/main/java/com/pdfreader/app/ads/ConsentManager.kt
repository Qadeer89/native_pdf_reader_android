package com.pdfreader.app.ads

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * DEC-004 (`docs/DECISIONS_AND_OPEN_QUESTIONS.md`): wraps Google's User Messaging Platform
 * (UMP) SDK, the mechanism that gates every ad request per AdMob's own GDPR/UK consent
 * policy — this isn't optional design, it's what AdMob requires for EEA/UK users.
 * [canRequestAds] only ever flips true once the UMP flow (consent-info update, then the
 * consent form if one is required) has actually completed; [com.pdfreader.app.ads.BannerAdView]
 * must never load an ad before that.
 */
class ConsentManager(private val activity: Activity) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(activity)

    private val _canRequestAds = MutableStateFlow(consentInformation.canRequestAds())
    val canRequestAds: StateFlow<Boolean> = _canRequestAds

    /**
     * Requests the latest consent status and, if required (e.g. an EEA/UK user), shows the
     * UMP consent form. Safe to call repeatedly (e.g. on every reader-screen entry) — the
     * SDK no-ops once the user's choice is already known. [onComplete] always runs exactly
     * once, whether or not a form was actually shown.
     */
    fun requestConsentAndLoadFormIfRequired(onComplete: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // A non-null callback argument here means the form itself failed to
                    // load/show; canRequestAds() still reflects the real consent state
                    // either way, so there's nothing else to branch on.
                    _canRequestAds.value = consentInformation.canRequestAds()
                    onComplete()
                }
            },
            {
                // requestConsentInfoUpdate itself failed (e.g. no network) — fall back to
                // whatever canRequestAds() already reports rather than blocking forever.
                _canRequestAds.value = consentInformation.canRequestAds()
                onComplete()
            },
        )
    }
}
