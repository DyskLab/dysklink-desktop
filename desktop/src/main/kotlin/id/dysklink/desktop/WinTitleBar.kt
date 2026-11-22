package id.dysklink.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import id.phonelink.android.core.CoreLog

/**
 * Windows draws a window's title bar itself, white unless the window asks for the dark one. DyskLink asks for it
 * while its theme is dark, so the title bar matches the window as on the Mac. Windows 10 before 1809 has no dark title
 * bar and keeps the light one.
 */
object WinTitleBar {
    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        runCatching { Native.load("dwmapi", Dwm::class.java) }.onFailure { CoreLog.w("DyskLink", "No dwmapi", it) }.getOrNull()
    }

    /** DWMWA_USE_IMMERSIVE_DARK_MODE: 20 since Windows 10 20H1, 19 on 1809 to 1909. */
    private val ATTRIBUTES = intArrayOf(20, 19)

    fun apply(window: java.awt.Window, dark: Boolean) {
        if (!Platform.isWindows) return
        val lib = dwm ?: return
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return
            val value = IntByReference(if (dark) 1 else 0)
            ATTRIBUTES.firstOrNull { lib.DwmSetWindowAttribute(handle, it, value, 4) == 0 }
        }.onFailure { CoreLog.w("DyskLink", "Dark title bar", it) }
    }
}
