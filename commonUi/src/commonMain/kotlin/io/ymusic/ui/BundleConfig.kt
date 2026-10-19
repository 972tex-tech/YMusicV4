package io.ymusic.ui

import io.ymusic.dynamicengine.JsConfigProvider
import io.ymusic.dynamicengine.SignedBundleRef
import io.ymusic.remoteconfig.BundleVersion
import io.ymusic.remoteconfig.RemoteConfig
import io.ymusic.remoteconfig.RemoteConfigData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One JS bundle's view of the remote config — [pick] names which (engineJs / playerJs). */
fun RemoteConfig.bundleConfig(pick: (RemoteConfigData) -> BundleVersion?): JsConfigProvider {
    val remoteConfig = this
    return object : JsConfigProvider {
        override val published: Flow<SignedBundleRef?> = remoteConfig.config.map { data ->
            pick(data)?.let { SignedBundleRef(it.version, it.url, it.signature) }
        }

        override fun checkUpdate(acceptMaxAgeMs: Long) = remoteConfig.checkUpdate(acceptMaxAgeMs)
    }
}
