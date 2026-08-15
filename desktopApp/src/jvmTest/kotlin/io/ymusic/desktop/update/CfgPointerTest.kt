package io.ymusic.desktop.update

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Switching versions must never leave an app that cannot start, so the config is the only thing
 * written and it is replaced whole. These use the real jpackage config shape.
 */
class CfgPointerTest {

    private val tmp = File("build/test-cfg-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    /** A macOS-shaped install; the Windows/Linux shape differs only in the app dir's depth. */
    private fun install(name: String = "YMusic.app"): File = File(tmp, name).apply {
        File(this, "Contents/app").mkdirs()
        File(this, "Contents/runtime/lib").mkdirs()
        File(this, "Contents/runtime/lib/modules").writeText("jvm")
        File(this, "Contents/app/a.jar").writeText("v1")
        File(this, "Contents/app/YMusic.cfg").writeText(
            """
            [Application]
            app.classpath=${'$'}APPDIR/a.jar
            app.mainclass=io.ymusic.desktop.MainKt
            app.classpath=${'$'}APPDIR/b.jar

            [JavaOptions]
            java-options=-Dcompose.application.resources.dir=${'$'}APPDIR/resources
            """.trimIndent(),
        )
    }

    @Test
    fun repointing_rewrites_only_the_classpath() {
        val root = install()
        val versionDir = File(root, "Contents/app-2").apply { mkdirs() }

        CfgPointer.repoint(root, versionDir)

        val cfg = CfgPointer.cfgFile(root)!!.readText()
        assertTrue("app.classpath=\$APPDIR/../app-2/a.jar" in cfg, cfg)
        assertTrue("app.classpath=\$APPDIR/../app-2/b.jar" in cfg, cfg)
        assertTrue("app.mainclass=io.ymusic.desktop.MainKt" in cfg, "the main class survives")
        // $APPDIR still resolves to the launcher's own dir, so non-classpath options must not move.
        assertTrue("resources.dir=\$APPDIR/resources" in cfg, "resource paths are left alone")
    }

    @Test
    fun the_config_is_replaced_whole_leaving_no_scratch_file() {
        val root = install()
        CfgPointer.repoint(root, File(root, "Contents/app-3").apply { mkdirs() })

        val leftovers = File(root, "Contents/app").listFiles().orEmpty().filter { it.name.endsWith(".new") }
        assertTrue(leftovers.isEmpty(), "a torn config would leave an app that cannot start: $leftovers")
        assertEquals("app-3", CfgPointer.currentVersionDirName(root))
    }

    @Test
    fun a_shipped_install_reports_no_version_directory_yet() {
        // Straight from the installer the classpath is still $APPDIR/… — nothing to prune.
        assertNull(CfgPointer.currentVersionDirName(install()))
    }

    @Test
    fun pruning_keeps_the_directory_the_config_points_at() {
        val root = install()
        File(root, "Contents/app-1").apply { mkdirs(); File(this, "x").writeText("old") }
        File(root, "Contents/app-2").apply { mkdirs(); File(this, "x").writeText("live") }
        CfgPointer.repoint(root, File(root, "Contents/app-2"))

        val removed = CfgPointer.pruneUnusedVersions(root)

        assertEquals(1, removed)
        assertTrue(File(root, "Contents/app-2/x").exists(), "the running version survives")
        assertTrue(!File(root, "Contents/app-1").exists())
        assertTrue(File(root, "Contents/app/YMusic.cfg").exists(), "the launcher config is not a version dir")
    }

    @Test
    fun a_runtime_change_cannot_be_applied_by_repointing() {
        // The launcher loads the JVM from runtime/ beside itself; only the classpath is switchable,
        // so a JDK bump has to go through the installer instead.
        val root = install()
        val installed = hashInstalledFiles(root)
        val sameRuntime = installed.toMutableMap().apply { put("Contents/app/a.jar", "different") }
        val newRuntime = installed.toMutableMap().apply { put("Contents/runtime/lib/modules", "different") }

        assertTrue(CfgPointer.runtimeMatches(root, sameRuntime, installed), "only the classpath moved")
        assertTrue(!CfgPointer.runtimeMatches(root, newRuntime, installed), "the JVM itself changed")
    }
}
