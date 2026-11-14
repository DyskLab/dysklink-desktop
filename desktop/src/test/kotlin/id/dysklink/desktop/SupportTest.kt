package id.dysklink.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportTest {
    private val facts = Support.Facts(
        appVersion = "1.0.0", platform = "Linux", osName = "Ubuntu 24.04 LTS", osVersion = "6.8.0-45-generic, amd64",
        session = "GNOME Wayland", javaVersion = "21.0.5", connectedPhones = 1,
        privateNames = listOf("budi-thinkpad", "budi", "Budi's PC"),
    )

    @Test
    fun messageHasVersionAndSystem() {
        val text = Support.message(facts)
        assertTrue(text.startsWith("Hi, I found a problem in DyskLink.\n\nWhat happened:\n\nWhat I expected:\n\n---\n"))
        assertTrue(text.contains("DyskLink for Linux 1.0.0"))
        assertTrue(text.contains("Ubuntu 24.04 LTS (6.8.0-45-generic, amd64), GNOME Wayland"))
        assertTrue(text.contains("Java 21.0.5"))
        assertTrue(text.endsWith("Connected: 1 phone"))
        assertTrue(Support.message(facts.copy(connectedPhones = 0)).endsWith("Connected: 0 phones"))
    }

    @Test
    fun messageLeavesOutThePcAndUserNames() {
        val text = Support.message(facts.copy(osName = "Budi's PC Linux", session = "budi-thinkpad X11"))
        assertFalse(text.contains("budi", ignoreCase = true))
        val live = Support.message(Support.currentFacts(2))
        assertFalse(live.contains(Platform.deviceName, ignoreCase = true))
        assertFalse(live.contains(System.getProperty("user.name"), ignoreCase = true))
        assertFalse(live.contains(System.getProperty("user.home")))
        assertTrue(live.contains("Java ${System.getProperty("java.version")}"))
    }

    @Test
    fun sessionNames() {
        val gnome = mapOf("XDG_SESSION_TYPE" to "wayland", "WAYLAND_DISPLAY" to "wayland-0", "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")
        assertEquals("GNOME Wayland", Support.session(false, gnome::get))
        val kde = mapOf("XDG_SESSION_TYPE" to "x11", "DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "KDE")
        assertEquals("KDE X11", Support.session(false, kde::get))
        assertEquals("XFCE X11", Support.session(false, mapOf("DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "XFCE")::get))
        assertEquals("Unknown desktop X11", Support.session(false, mapOf("DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "Budi's own desktop")::get))
        assertEquals("Windows", Support.session(true) { null })
    }

    @Test
    fun urlEncodesNewlinesAmpersandAndHash() {
        assertEquals("https://wa.me/6285117241230?text=a%20b%0Ac%26d%23e%2Bf", Support.url("a b\nc&d#e+f"))
        val url = Support.url(Support.message(facts))
        assertTrue(url.startsWith("https://wa.me/${Support.WHATSAPP_NUMBER}?text=Hi%2C%20I%20found"))
        assertTrue(url.contains("%0A%0AWhat%20happened%3A"))
        assertFalse(url.contains("\n") || url.contains(" ") || url.contains("#"))
    }
}
