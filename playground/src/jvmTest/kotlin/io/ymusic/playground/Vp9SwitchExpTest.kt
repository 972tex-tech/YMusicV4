package io.ymusic.playground

import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

class Vp9SwitchExpTest {
    @Test
    fun second_sabr_vid_278_records_ump_and_append() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "exp278")
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 60_000
            var proof = ""
            var pos = 0L
            while (System.currentTimeMillis() < deadline) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                val switched = proof.contains("mse exp278 n=2") && proof.contains("to=278")
                val appendAfter = switched && proof.substringAfter("mse exp278 n=2").contains("mse append a=0")
                if (switched && appendAfter && pos >= 3_000) break
                delay(400)
            }
            proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            dumpExp278(proof, pos)
            assertTrue(proof.contains("mse exp278 skip /player 144p rewrite"), "rewrote /player 144p")
            assertTrue(!proof.contains("mse fake-fmt 144p"), "144p was faked in /player")
            assertTrue(proof.contains("mse exp278 n=1 pass"), "first SABR was not passed through")
            assertTrue(
                proof.contains("mse exp278 n=2") && proof.contains("to=278"),
                "second SABR did not keep only 278",
            )
            assertTrue(proof.contains("FORMAT_INIT") && proof.contains("itag=278"), "no FORMAT_INIT 278")
            assertTrue(
                proof.contains("mse changeType a=0 from=video/mp4"),
                "no changeType av01→vp9. proof missing changeType",
            )
            assertTrue(
                proof.contains("mse addSourceBuffer video/mp4"),
                "first path did not init av01/mp4 SB",
            )
        } finally {
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "0AHAkBrBg5I"

        private fun dumpExp278(proof: String, pos: Long) {
            val keys = listOf(
                "mse exp278",
                "mse fake-fmt",
                "mse addSourceBuffer",
                "mse changeType",
                "mse append a=0",
                "FORMAT_INIT",
                "MEDIA_HEADER hid=",
                "sabr sabr=",
                "sabr HTTP",
            )
            println("--- exp278 pos=$pos ---")
            for (line in proof.lineSequence()) {
                if (keys.any { line.contains(it) }) println("  $line")
            }
            println("--- /exp278 ---")
        }
    }
}
