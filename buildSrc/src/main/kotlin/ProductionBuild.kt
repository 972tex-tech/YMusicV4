import org.gradle.api.Project

/**
 * Whether this build makes a shipped artifact, which bakes PRODUCTION into Kotlin, C and the JS bundles
 * and forces DEBUG_BUILD off. `-Pproduction=true|false` wins; otherwise a release task on the command line
 * (assembleRelease, bundleRelease, packageReleaseDmg, …) or Xcode's Release configuration running
 * embedAndSignAppleFrameworkForXcode turns it on, so no release path can ship without it.
 */
fun Project.isProductionBuild(): Boolean {
    providers.gradleProperty("production").orNull?.let { return it.toBoolean() }
    val tasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
    if (tasks.any { RELEASE_TASK.matches(it) }) return true
    val xcodeRelease = providers.environmentVariable("CONFIGURATION").orNull?.startsWith("Release") == true
    return xcodeRelease && "embedAndSignAppleFrameworkForXcode" in tasks
}

private val RELEASE_TASK = Regex("(assemble|bundle|install|package|notarize)\\w*Release\\w*")
