package com.eddies.app

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.design.EddiesTheme
import com.eddies.app.core.ui.WindowSecurityPolicy
import com.eddies.app.navigation.EddiesNavHost
import com.eddies.app.navigation.RootViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.activity.viewModels

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    @Inject lateinit var windowSecurity: WindowSecurityPolicy

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until settings are read, so the app never paints in the
        // wrong theme and then snaps to the right one.
        splash.setKeepOnScreenCondition { rootViewModel.state.value.loading }
        enableEdgeToEdge()

        setContent {
            val state by rootViewModel.state.collectAsStateWithLifecycle()

            // Keeps balances out of the recents thumbnail. Applied from state so
            // toggling the setting takes effect without a restart. The policy is
            // chosen at build time: the demo build never secures the window,
            // because FLAG_SECURE blocks screenshots and taking them is the only
            // reason that build exists.
            applySecureFlag(windowSecurity.shouldSecureWindow(state.hideInRecents))

            EddiesTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
                EddiesNavHost(locked = state.locked, onUnlocked = rootViewModel::onUnlocked)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        rootViewModel.onBackgrounded()
    }

    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
