package com.rommmobile.app.feature.settings

import android.net.Uri
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommTopBar

internal const val PRIVACY_POLICY_URL = "https://mriverodorta.github.io/RomMMobileS/privacy/"

@Composable
internal fun PrivacyPolicyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.settings_privacy_policy), onBack = onBack)
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.buildUpon().fragment(null).build() != Uri.parse(PRIVACY_POLICY_URL)
                    }
                    setOnKeyListener { _, key, event ->
                        val direction = when (key) {
                            KeyEvent.KEYCODE_DPAD_UP -> -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            else -> return@setOnKeyListener false
                        }
                        if (event.action == KeyEvent.ACTION_DOWN) scrollBy(0, direction * height / 3)
                        true
                    }
                    loadUrl(PRIVACY_POLICY_URL)
                    post { requestFocus() }
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
