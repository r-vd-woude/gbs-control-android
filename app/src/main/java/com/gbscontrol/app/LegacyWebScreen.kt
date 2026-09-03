package com.gbscontrol.app

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The firmware's own web interface, kept as an escape hatch for the things the native screens do
 * not cover yet: Wi-Fi provisioning, backup and restore, and firmware update.
 *
 * Navigation is pinned to the selected device. The device serves unauthenticated cleartext HTTP, so
 * a page it renders must not be able to send the WebView somewhere else, and file and content URLs
 * stay off because nothing the firmware serves needs them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegacyWebScreen(host: String) {
    val context = LocalContext.current
    var loading by remember(host) { mutableStateOf(true) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    val allowedHost = remember(host) { runCatching { HostAddress.normalize(host) }.getOrNull() }

    val webView = remember(host) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.host?.let { runCatching { HostAddress.normalize(it) }.getOrNull() }
                    // true means "handled here", which for a foreign host means "not loaded at all".
                    return !target.equals(allowedHost, ignoreCase = true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) {
                        loading = false
                        error = failure.description?.toString() ?: "Could not load firmware interface"
                    }
                }
            }
            loadUrl(HostAddress.httpUrl(host))
        }
    }

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }
    DisposableEffect(webView) { onDispose { webView.destroy() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
