package io.ymusic.android

import android.content.Context
import android.content.SharedPreferences
import io.ymusic.settings.StringBackedSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [io.ymusic.settings.SettingsStore] persisted in a SharedPreferences file. */
class PrefsSettingsStore(private val prefs: SharedPreferences) : StringBackedSettingsStore() {
    init {
        seed(prefs.all.mapValues { (_, v) -> v.toString() })
    }

    override suspend fun persist(key: String, value: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString(key, value).commit() }
    }

    companion object {
        fun open(context: Context): PrefsSettingsStore =
            PrefsSettingsStore(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
    }
}
