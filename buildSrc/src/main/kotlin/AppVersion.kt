import org.gradle.api.Project

/** The version name every platform ships under: `ymusic.versionName` in gradle.properties. */
fun Project.appVersionName(): String = providers.gradleProperty("ymusic.versionName").get()
