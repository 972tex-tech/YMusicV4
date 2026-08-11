package io.ymusic.desktop

import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Dev window: a WINDOWED CEF browser sharing the app's ONE Chromium profile (`~/.ymusic/jcef-cache`),
 * DevTools attached — its Application → Cookies / console inspect the live store the engine, the
 * player and the login browser all use. Starts on about:blank; the address field on top navigates
 * (cookies list per page origin, so go to youtube.com to see that jar). A real Chrome can't open
 * this profile (version mismatch + Chromium's singleton profile lock while the app runs), so the
 * inspector runs in-process; the remote-debugging port (9222) stays as the external alternative.
 */
internal fun openCookieInspector(cefManager: CefManager) {
    SwingUtilities.invokeLater {
        val client = cefManager.createExtraClient()
        var browser: CefBrowser? = null
        // openDevTools before the native browser exists is a silent no-op — defer it to
        // onAfterCreated (which also fires for the DevTools browser itself, hence the identity guard).
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(created: CefBrowser) {
                if (created === browser) SwingUtilities.invokeLater { created.openDevTools() }
            }
        })
        val b = client.createBrowser("about:blank", false, false)
        browser = b
        val address = JTextField("about:blank").apply {
            addActionListener {
                val raw = text.trim()
                if (raw.isNotEmpty()) {
                    b.loadURL(if (raw.contains("://") || raw.startsWith("about:")) raw else "https://$raw")
                }
            }
        }
        JFrame("Cookie inspector — shared CEF profile (~/.ymusic/jcef-cache)").apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    client.dispose() // takes the browser (and its DevTools window) with it
                }
            })
            contentPane.layout = BorderLayout()
            contentPane.add(address, BorderLayout.NORTH)
            contentPane.add(b.uiComponent, BorderLayout.CENTER)
            setSize(1100, 780)
            setLocationByPlatform(true)
            isVisible = true
        }
    }
}
