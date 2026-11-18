package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.SavedCommand
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What happens to one key while the keyboard is locked. */
enum class LockVerdict { PASS, SWALLOW, UNLOCK }

/**
 * Decides what a key does while the keyboard is locked: this PC's own keys are held back, and Ctrl+Alt+Shift+U unlocks.
 * Input programs post (DyskLink's own, typing from a phone or a Mac, among them) passes. The modifiers are tracked here,
 * since their own presses are held back and Windows doesn't see them as down. Keys are never stored or sent anywhere.
 */
class KeyboardLockKeys {
    private val held = mutableSetOf<Int>()

    fun key(vk: Int, down: Boolean, injected: Boolean): LockVerdict {
        if (injected) return LockVerdict.PASS
        val modifier = MODIFIERS[vk]
        if (modifier != null) {
            if (down) held += modifier else held -= modifier
            return LockVerdict.SWALLOW
        }
        if (down && vk == VK_U && held.containsAll(UNLOCK_MODIFIERS)) return LockVerdict.UNLOCK
        return LockVerdict.SWALLOW
    }

    fun reset() = held.clear()

    companion object {
        const val UNLOCK_LABEL = "Ctrl+Alt+Shift+U"
        const val VK_U = 0x55
        private const val CTRL = 1
        private const val ALT = 2
        private const val SHIFT = 3
        private val UNLOCK_MODIFIERS = setOf(CTRL, ALT, SHIFT)
        /** Both sides' virtual keys, and the side-less ones some keyboards send. */
        private val MODIFIERS = mapOf(
            0x10 to SHIFT, 0xA0 to SHIFT, 0xA1 to SHIFT,
            0x11 to CTRL, 0xA2 to CTRL, 0xA3 to CTRL,
            0x12 to ALT, 0xA4 to ALT, 0xA5 to ALT,
        )
    }
}

/** Holds this PC's keys back while it runs. */
interface KeyBlocker {
    /** False when the system doesn't allow it. [onUnlock] is called on the blocker's own thread. */
    fun start(onUnlock: () -> Unit): Boolean
    fun stop()
}

/**
 * Windows: a WH_KEYBOARD_LL hook on a thread of its own that runs a message loop, as in [WindowsCapture]. It only
 * answers from [KeyboardLockKeys], so it stays well inside LowLevelHooksTimeout. Ctrl+Alt+Del and Win+L are Windows'
 * own and always work; the sign-in screen never goes through the hook.
 */
class WindowsKeyBlocker : KeyBlocker {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun SetWindowsHookExW(id: Int, proc: WindowsCapture.HookProc, module: Pointer?, threadId: Int): Pointer?
        fun UnhookWindowsHookEx(hook: Pointer): Boolean
        fun CallNextHookEx(hook: Pointer?, code: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
        fun GetMessageW(msg: Pointer, window: Pointer?, min: Int, max: Int): Int
        fun PostThreadMessageW(thread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetModuleHandleW(name: WString?): Pointer?
        fun GetCurrentThreadId(): Int
        fun GetLastError(): Int
    }

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private val kernel32: Kernel32? by lazy { runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() }

    @Volatile private var threadId = 0
    @Volatile private var hookThread: Thread? = null
    @Volatile private var hook: Pointer? = null
    @Volatile private var onUnlock: () -> Unit = {}
    private val keys = KeyboardLockKeys()
    // Kept in a field: JNA frees a callback whose object is collected, and Windows would call freed memory.
    private val proc = object : WindowsCapture.HookProc {
        override fun callback(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? = keyboard(code, wParam, lParam)
    }

    override fun start(onUnlock: () -> Unit): Boolean {
        val lib = user32 ?: return false
        val k = kernel32 ?: return false
        this.onUnlock = onUnlock
        if (hookThread?.isAlive == true && hook != null) return true
        stop()
        keys.reset()
        val ready = CountDownLatch(1)
        var ok = false
        hookThread = thread(isDaemon = true, name = "dysk-keyboard-lock", priority = Thread.MAX_PRIORITY) {
            threadId = k.GetCurrentThreadId()
            hook = lib.SetWindowsHookExW(WH_KEYBOARD_LL, proc, k.GetModuleHandleW(null), 0)
            ok = hook != null
            if (!ok) CoreLog.w("DyskLink", "Keyboard lock: SetWindowsHookEx failed: error ${k.GetLastError()}")
            ready.countDown()
            val msg = Memory(48)
            try {
                while (ok) {
                    if (lib.GetMessageW(msg, null, 0, 0) <= 0) break
                }
            } catch (error: Throwable) {
                CoreLog.w("DyskLink", "Keyboard lock thread", error)
            } finally {
                hook?.let { runCatching { lib.UnhookWindowsHookEx(it) } }
                hook = null
            }
        }
        ready.await(2, TimeUnit.SECONDS)
        return ok
    }

    override fun stop() {
        val id = threadId
        if (id != 0) runCatching { user32?.PostThreadMessageW(id, WM_QUIT, null, null) }
        runCatching { hookThread?.join(1_000) }
        hookThread = null
        threadId = 0
    }

    private fun keyboard(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
        val next = { runCatching { user32?.CallNextHookEx(hook, code, wParam, lParam) }.getOrNull() }
        if (code < 0 || lParam == null) return next()
        return try {
            val vk = lParam.getInt(0)
            val flags = lParam.getInt(8)
            when (keys.key(vk, down = flags and LLKHF_UP == 0, injected = flags and LLKHF_INJECTED != 0)) {
                LockVerdict.PASS -> next()
                LockVerdict.SWALLOW -> SWALLOW
                LockVerdict.UNLOCK -> {
                    runCatching { onUnlock() }
                    SWALLOW
                }
            }
        } catch (error: Throwable) {
            // Better a keyboard that works than one stuck locked.
            CoreLog.w("DyskLink", "Keyboard lock hook", error)
            next()
        }
    }

    private companion object {
        const val WH_KEYBOARD_LL = 13
        const val WM_QUIT = 0x0012
        const val LLKHF_INJECTED = 0x10
        const val LLKHF_UP = 0x80
        val SWALLOW: Pointer = Pointer(1)
    }
}

/** The Keyboard lock card, the tray, and the small window while it's locked. */
data class KeyboardLockView(
    val locked: Boolean = false,
    /** How long a lock lasts; 0 is until it's unlocked. */
    val minutes: Int = 5,
    /** When it unlocks by itself, in milliseconds since the epoch; null without a time limit or while unlocked. */
    val endsAt: Long? = null,
    val problem: String? = null,
) {
    fun remainingText(now: Long): String {
        val end = endsAt ?: return "Until you unlock it"
        val seconds = ((end - now).coerceAtLeast(0) + 999) / 1000
        return "%d:%02d left".format(seconds / 60, seconds % 60)
    }
}

/**
 * Locks this PC's keyboard for a while, to clean it or with a cat or a child about. The mouse keeps working, so the
 * Unlock button is always there; [KeyboardLockKeys.UNLOCK_LABEL] unlocks it, the time runs out, or a paired phone
 * unlocks it from its Commands list. Nothing in Windows changes: quitting DyskLink ends it too.
 */
class KeyboardLockService(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val blocker: KeyBlocker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _view = MutableStateFlow(KeyboardLockView(minutes = settings.int(MINUTES_KEY)?.takeIf { m -> DURATIONS.any { it.first == m } } ?: 5))
    val view: StateFlow<KeyboardLockView> = _view.asStateFlow()
    /** Told when it locks or unlocks, so phones get the Unlock command only while it's needed. */
    var onChange: () -> Unit = {}
    private var timer: Job? = null

    fun setMinutes(minutes: Int) {
        if (DURATIONS.none { it.first == minutes }) return
        settings.set(MINUTES_KEY, minutes.toString())
        _view.update { it.copy(minutes = minutes) }
    }

    @Synchronized
    fun lock() {
        if (_view.value.locked) return
        if (!blocker.start(onUnlock = { scope.launch { unlock() } })) {
            _view.update { it.copy(problem = "Windows didn't allow it. Try again, or restart DyskLink.") }
            return
        }
        val minutes = _view.value.minutes
        val endsAt = if (minutes > 0) clock() + minutes * 60_000L else null
        _view.update { it.copy(locked = true, endsAt = endsAt, problem = null) }
        CoreLog.i("DyskLink", "KEYBOARD_LOCKED minutes=$minutes")
        timer = endsAt?.let { end ->
            scope.launch {
                while (clock() < end) delay((end - clock()).coerceIn(50, 1_000))
                unlock()
            }
        }
        onChange()
    }

    @Synchronized
    fun unlock() {
        if (!_view.value.locked) return
        blocker.stop()
        timer?.cancel()
        timer = null
        _view.update { it.copy(locked = false, endsAt = null) }
        CoreLog.i("DyskLink", "KEYBOARD_UNLOCKED")
        onChange()
    }

    fun toggle() = if (_view.value.locked) unlock() else lock()

    /** "Unlock the keyboard" in the phone's Commands list, while it's locked. */
    fun commands(): List<SavedCommand> =
        if (_view.value.locked) listOf(SavedCommand(COMMAND_ID, "Unlock the keyboard", "")) else emptyList()

    /** The phone's answer, or null when [id] isn't this one's command. */
    fun runCommand(id: String): String? {
        if (id != COMMAND_ID) return null
        if (!_view.value.locked) return "The keyboard isn't locked."
        unlock()
        return "The keyboard is unlocked."
    }

    fun stop() = unlock()

    companion object {
        const val COMMAND_ID = "keyboard:unlock"
        private const val MINUTES_KEY = "keyboardLock.minutes"
        val DURATIONS = listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 0 to "Until I unlock it")
        fun label(minutes: Int) = DURATIONS.firstOrNull { it.first == minutes }?.second ?: "$minutes minutes"
    }
}
