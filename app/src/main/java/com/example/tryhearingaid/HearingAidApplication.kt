package com.example.tryhearingaid

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point required by Hilt for dependency injection setup.
 *
 * Annotated with [HiltAndroidApp] so that Hilt generates its component hierarchy
 * at compile time and the application-scoped component is available throughout
 * the app lifecycle.
 */
@HiltAndroidApp
class HearingAidApplication : Application()
