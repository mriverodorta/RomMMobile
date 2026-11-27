package com.rommmobile.app.feature.saves

import android.content.Context
import android.content.Intent
import com.rommmobile.app.core.design.isTelevision
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun retroArchCloudSyncUrl(active: String?, local: String?, remote: String?): String? {
    val addresses = listOfNotNull(active, local, remote).mapNotNull { it.toHttpUrlOrNull() }
    val address = addresses.firstOrNull { it.isHttps } ?: addresses.firstOrNull() ?: return null
    return address.newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .addPathSegments("api/sync/retroarch/")
        .build().toString()
}

internal fun retroArchLaunchIntent(context: Context): Intent? {
    val packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    val isTelevision = context.isTelevision()
    return packages.firstNotNullOfOrNull { packageName ->
        val manager = context.packageManager
        (if (isTelevision) manager.getLeanbackLaunchIntentForPackage(packageName) else null)
            ?: manager.getLaunchIntentForPackage(packageName)
    }
}
