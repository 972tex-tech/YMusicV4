package ymusic.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.settings.StringBackedSettingsStore

/** [ymusic.settings.SettingsStore] persisted in a SharedPreferences file. */
class PrefsSettingsStore(private val prefs: SharedPreferences) : StringBackedSettingsStore() {
    init {
        seed(prefs.all.mapValues { (_, v) -> v.toString() })
    }

    override suspend fun persist() {
        val values = snapshot()
        withContext(Dispatchers.IO) {
            prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.commit()
        }
    }

    companion object {
        fun open(context: Context): PrefsSettingsStore =
            PrefsSettingsStore(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
    }
}
