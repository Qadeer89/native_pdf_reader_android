package com.pdfreader.app.ads

/**
 * DEC-004 (`docs/DECISIONS_AND_OPEN_QUESTIONS.md`): AdMob ad unit ids. [BANNER_AD_UNIT_ID]
 * is Google's own published **test** banner unit — safe to ship in a public repo and
 * guaranteed to always serve a test ad, used here because no real AdMob account exists yet
 * (DEC-004 consequence (2) — a real App ID/ad unit requires the user to create one, the
 * same external-account-creation gate as DEC-003's Firebase project). Swap this for the
 * real banner ad unit id once that account exists; the App ID in `AndroidManifest.xml`'s
 * `com.google.android.gms.ads.APPLICATION_ID` meta-data must be swapped at the same time.
 */
internal const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
