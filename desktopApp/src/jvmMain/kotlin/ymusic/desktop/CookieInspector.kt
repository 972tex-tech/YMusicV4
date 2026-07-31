package ymusic.desktop

import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Dev window: a WINDOWED CEF browser on youtube.com sharing the app's ONE Chromium profile
 * (`~/.ymusic/jcef-cache`), with DevTools attached — Application → Cookies inspects the live store
 * the engine, the player and the login browser all use. A real Chrome can't open this profile
 * (version mismatch + Chromium's singleton profile lock while the app runs), so the inspector runs
 * in-process. The remote-debugging port (9222) remains the alternative for a full external DevTools.
 */
internal fun openCookieInspector(cefManager: CefManager) {
    SwingUtilities.invokeLater {
        val client = cefManager.createExtraClient()
        val browser = client.createBrowser("https://www.youtube.com", false, false)
        JFrame("Cookie inspector — shared CEF profile (~/.ymusic/jcef-cache)").apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    client.dispose() // takes the browser (and its DevTools window) with it
                }
            })
            contentPane.add(browser.uiComponent)
            setSize(1100, 780)
            setLocationByPlatform(true)
            isVisible = true
        }
        browser.openDevTools()
    }
}
