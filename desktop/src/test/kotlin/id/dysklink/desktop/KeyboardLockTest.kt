package id.dysklink.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class KeyboardLockTest {
    private class FakeBlocker(var allowed: Boolean = true) : KeyBlocker {
        var running = false
        var unlock: () -> Unit = {}
        override fun start(onUnlock: () -> Unit): Boolean {
            if (!allowed) return false
            running = true
            unlock = onUnlock
            return true
        }
        override fun stop() { running = false }
    }

    private fun settings() = Settings(File.createTempFile("dysk-lock", ".properties").also { it.delete(); it.deleteOnExit() })

    @Test
    fun ownKeysAreHeldBackAndInjectedOnesPass() {
        val keys = KeyboardLockKeys()
        assertEquals(LockVerdict.SWALLOW, keys.key(0x41, down = true, injected = false))
        assertEquals(LockVerdict.SWALLOW, keys.key(0x41, down = false, injected = false))
        assertEquals(LockVerdict.SWALLOW, keys.key(0x5B, down = true, injected = false))
        // Typing from a phone or a Mac arrives through SendInput.
        assertEquals(LockVerdict.PASS, keys.key(0x41, down = true, injected = true))
    }

    @Test
    fun ctrlAltShiftUUnlocks() {
        val keys = KeyboardLockKeys()
        assertEquals(LockVerdict.SWALLOW, keys.key(KeyboardLockKeys.VK_U, down = true, injected = false))
        keys.key(0xA2, true, false)
        keys.key(0xA4, true, false)
        assertEquals(LockVerdict.SWALLOW, keys.key(KeyboardLockKeys.VK_U, down = true, injected = false), "Ctrl+Alt+U alone doesn't")
        keys.key(0xA1, true, false)
        assertEquals(LockVerdict.UNLOCK, keys.key(KeyboardLockKeys.VK_U, down = true, injected = false))
        assertEquals(LockVerdict.SWALLOW, keys.key(KeyboardLockKeys.VK_U, down = false, injected = false), "the release isn't a second unlock")
        keys.key(0xA1, false, false)
        assertEquals(LockVerdict.SWALLOW, keys.key(KeyboardLockKeys.VK_U, down = true, injected = false), "letting go of Shift ends the combo")
        // Injected modifiers don't count toward it.
        val other = KeyboardLockKeys()
        listOf(0x11, 0x12, 0x10).forEach { other.key(it, true, injected = true) }
        assertEquals(LockVerdict.SWALLOW, other.key(KeyboardLockKeys.VK_U, down = true, injected = false))
    }

    @Test
    fun locksUnlocksAndRemembersTheLength() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blocker = FakeBlocker()
        val store = settings()
        val lock = KeyboardLockService(scope, store, blocker)
        var changes = 0
        lock.onChange = { changes++ }
        assertTrue(lock.commands().isEmpty())
        assertEquals("The keyboard isn't locked.", lock.runCommand(KeyboardLockService.COMMAND_ID))
        assertNull(lock.runCommand("other"))
        lock.setMinutes(0)
        lock.setMinutes(7)
        assertEquals(0, lock.view.value.minutes, "lengths outside the list are ignored")
        assertEquals(0, KeyboardLockService(scope, store, FakeBlocker()).view.value.minutes, "the length is remembered")
        lock.lock()
        assertTrue(lock.view.value.locked && blocker.running)
        assertNull(lock.view.value.endsAt)
        assertEquals("Until you unlock it", lock.view.value.remainingText(0))
        assertEquals(listOf("Unlock the keyboard"), lock.commands().map { it.name })
        assertEquals("The keyboard is unlocked.", lock.runCommand(KeyboardLockService.COMMAND_ID))
        assertFalse(lock.view.value.locked || blocker.running)
        assertEquals(2, changes)
        lock.toggle()
        assertTrue(lock.view.value.locked)
        lock.toggle()
        assertFalse(lock.view.value.locked)
        scope.cancel()
    }

    @Test
    fun theShortcutAndTheTimerUnlock() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blocker = FakeBlocker()
        var now = 1_000_000L
        val lock = KeyboardLockService(scope, settings(), blocker, clock = { now })
        lock.setMinutes(1)
        lock.lock()
        assertEquals(now + 60_000, lock.view.value.endsAt)
        assertEquals("1:00 left", lock.view.value.remainingText(now))
        assertEquals("0:01 left", lock.view.value.remainingText(now + 59_001))
        blocker.unlock()
        waitFor { !lock.view.value.locked }
        lock.lock()
        assertTrue(lock.view.value.locked)
        now += 60_000
        waitFor { !lock.view.value.locked }
        assertFalse(blocker.running)
        scope.cancel()
    }

    @Test
    fun aRefusedHookSaysSo() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = KeyboardLockService(scope, settings(), FakeBlocker(allowed = false))
        lock.lock()
        assertFalse(lock.view.value.locked)
        assertTrue(lock.view.value.problem != null)
        scope.cancel()
    }

    @Test
    fun theTrayItemFollowsTheLock() {
        val config = TrayMenuConfig(listOf(TraySection.KeyboardLock), setOf(TraySection.KeyboardLock))
        var toggled = 0
        val actions = TrayMenuActions(toggleKeyboardLock = { toggled++ })
        assertEquals(listOf("Report a problem", "Quit DyskLink"), TrayMenu.build(config, TrayMenuState(), actions).filterIsInstance<TrayEntry.Item>().map { it.label }, "hidden where there's none")
        val menu = TrayMenu.build(config, TrayMenuState(keyboardLock = "Unlock keyboard"), actions)
        (menu.first() as TrayEntry.Item).onClick()
        assertEquals("Unlock keyboard", (menu.first() as TrayEntry.Item).label)
        assertEquals(1, toggled)
        assertFalse(TraySection.KeyboardLock in TraySection.DEFAULT_SHOWN)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(condition())
    }
}
