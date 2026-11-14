package id.dysklink.desktop

/**
 * "Report a problem": opens WhatsApp to the developer with a short message the user can edit.
 * The message holds only the app version, the OS and its version, the desktop session, the Java version, and how
 * many phones are connected. It never holds the PC's name, user names, addresses, pairing IDs, or paths.
 */
object Support {
    /** The developer's WhatsApp number, in wa.me form (country code, no plus or spaces). */
    const val WHATSAPP_NUMBER = "6285117241230"
    /** The same number as people read it. */
    const val WHATSAPP_NUMBER_DISPLAY = "+62 851-1724-1230"

    const val REPORT_TITLE = "Report a problem"
    const val REPORT_DETAIL = "Send a message on WhatsApp. It includes the app version and system so the problem is easier to find; you can edit it before sending."
    const val FAILURE_MESSAGE = "Couldn't open WhatsApp. Message $WHATSAPP_NUMBER_DISPLAY instead."

    data class Facts(
        val appVersion: String,
        /** "Windows" or "Linux". */
        val platform: String,
        /** Such as "Windows 11" or "Ubuntu 24.04 LTS". */
        val osName: String,
        /** The kernel or Windows version and the CPU architecture, such as "10.0, amd64". */
        val osVersion: String,
        /** Such as "Windows", "GNOME Wayland" or "KDE X11". */
        val session: String,
        val javaVersion: String,
        val connectedPhones: Int,
        /** Names that must never appear in the message, such as the PC's name and the user's name. */
        val privateNames: List<String> = emptyList(),
    )

    fun message(facts: Facts): String {
        val phones = if (facts.connectedPhones == 1) "1 phone" else "${facts.connectedPhones} phones"
        val text = "Hi, I found a problem in DyskLink.\n\n" +
            "What happened:\n\n" +
            "What I expected:\n\n" +
            "---\n" +
            "DyskLink for ${facts.platform} ${facts.appVersion}\n" +
            "${facts.osName} (${facts.osVersion}), ${facts.session}\n" +
            "Java ${facts.javaVersion}\n" +
            "Connected: $phones"
        return scrub(text, facts.privateNames)
    }

    /** Takes out any private name that slipped into a field, such as a distro name the user changed to their own. */
    fun scrub(text: String, names: List<String>): String =
        names.map { it.trim() }.filter { it.length >= 2 }
            .fold(text) { acc, name -> acc.replace(name, "[hidden]", ignoreCase = true) }

    /** Encodes everything but letters, digits, and `-._~`, so newlines, `&`, `#`, and `+` survive in the query. */
    fun url(message: String): String {
        val encoded = buildString {
            message.toByteArray(Charsets.UTF_8).forEach { byte ->
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~")) append(c)
                else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return "https://wa.me/$WHATSAPP_NUMBER?text=$encoded"
    }

    /** Such as "Windows", "GNOME Wayland", "KDE X11", or the desktop's own name for others. */
    fun session(isWindows: Boolean, env: (String) -> String?): String {
        if (isWindows) return "Windows"
        val kind = if (HostChecks.isWayland(env)) "Wayland" else if (env("DISPLAY") != null) "X11" else "no display"
        val desktop = when (HostChecks.loginDesktop(env("XDG_CURRENT_DESKTOP"))) {
            LoginDesktop.Gnome -> "GNOME"
            LoginDesktop.Kde -> "KDE"
            // Only the last part, and only a short plain word: it's a desktop name such as "sway" or "XFCE", nothing personal.
            LoginDesktop.Other -> env("XDG_CURRENT_DESKTOP").orEmpty().split(':').last().trim()
                .takeIf { it.length in 1..24 && it.all { c -> c.isLetterOrDigit() || c == '-' } } ?: "Unknown desktop"
        }
        return "$desktop $kind"
    }

    fun currentFacts(connectedPhones: Int): Facts = Facts(
        appVersion = Platform.version,
        platform = if (Platform.isWindows) "Windows" else "Linux",
        osName = Platform.model,
        osVersion = listOfNotNull(System.getProperty("os.version"), System.getProperty("os.arch")).joinToString(", "),
        session = session(Platform.isWindows, System::getenv),
        javaVersion = System.getProperty("java.version") ?: "?",
        connectedPhones = connectedPhones,
        privateNames = listOfNotNull(Platform.deviceName, System.getProperty("user.name"), System.getenv("USERNAME"), System.getenv("USER")),
    )

    /** Opens WhatsApp (or the browser, which hands wa.me to WhatsApp). False when nothing could open the link. */
    fun report(connectedPhones: Int): Boolean = Platform.tryBrowse(url(message(currentFacts(connectedPhones))))
}
