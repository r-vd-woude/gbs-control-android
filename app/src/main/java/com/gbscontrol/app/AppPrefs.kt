package com.gbscontrol.app

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("gbs_prefs", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) {
            val normalized = HostAddress.normalize(value)
            prefs.edit()
                .putString(KEY_HOST, normalized)
                .putStringSet(KEY_HOSTS, rememberedHosts.plus(normalized))
                .apply()
        }

    val rememberedHosts: Set<String>
        get() = prefs.getStringSet(KEY_HOSTS, setOf(DEFAULT_HOST)).orEmpty().plus(DEFAULT_HOST)

    fun forgetHost(value: String) {
        if (value == DEFAULT_HOST || value == host) return
        prefs.edit().putStringSet(KEY_HOSTS, rememberedHosts.minus(value)).apply()
    }

    fun urlFor(hostOverride: String? = null): String = HostAddress.httpUrl(hostOverride ?: host)

    companion object {
        const val DEFAULT_HOST = "gbscontrol.local"
        private const val KEY_HOST = "host"
        private const val KEY_HOSTS = "hosts"
    }
}
