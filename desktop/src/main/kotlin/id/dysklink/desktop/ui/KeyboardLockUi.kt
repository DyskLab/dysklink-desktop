package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.KeyboardLockKeys
import id.dysklink.desktop.KeyboardLockService
import id.dysklink.desktop.KeyboardLockView
import id.dysklink.desktop.TraySection
import kotlinx.coroutines.delay

/** The time left, counted every second while it's locked. */
@Composable
internal fun lockClock(view: KeyboardLockView, clock: () -> Long): Long {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(view.locked, view.endsAt) {
        while (view.locked) {
            now = clock()
            delay(1_000)
        }
    }
    return now
}

@Composable
fun KeyboardLockCard(model: DeskModel, clock: () -> Long = System::currentTimeMillis) {
    val flow = model.keyboardLock ?: return
    val colors = Dysk.colors
    val view by flow.collectAsState()
    val now = lockClock(view, clock)
    GlassCard {
        CardTitle(DyskIcons.Keyboard, "Keyboard lock", colors.coral)
        Spacer(Modifier.height(Space.xs))
        Body("Turns off this PC's keyboard for a while, to clean it or with a cat or a child about. The mouse and touchpad keep working.")
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text(if (view.locked) "Keyboard locked" else "Lock for", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                if (view.locked) {
                    Text(view.remainingText(now), color = colors.textSecondary, fontSize = 12.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KeyboardLockService.DURATIONS.forEach { (minutes, label) ->
                            TabPill(label, minutes == view.minutes) { model.setKeyboardLockMinutes(minutes) }
                        }
                    }
                }
            }
            if (view.locked) SecondaryButton("Unlock", tint = colors.coralText) { model.setKeyboardLocked(false) }
            else PrimaryButton("Lock", icon = DyskIcons.Keyboard) { model.setKeyboardLocked(true) }
        }
        view.problem?.takeIf { !view.locked }?.let { Spacer(Modifier.height(6.dp)); Text(it, color = colors.amberText, fontSize = 12.sp) }
        Spacer(Modifier.height(Space.xs))
        Text(
            "To unlock: click Unlock in the small window at the bottom of the screen, press ${KeyboardLockKeys.UNLOCK_LABEL}, wait for the time to run out, " +
                "or choose Unlock the keyboard in Commands on your phone. Ctrl+Alt+Del and Windows+L always work. Typing from your phone or a Mac still works.",
            color = colors.textFaint, fontSize = 11.5.sp, lineHeight = 15.sp,
        )
        model.trayMenu?.let { trayFlow ->
            val tray by trayFlow.collectAsState()
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show in the tray menu", Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.sp)
                DyskSwitch(TraySection.KeyboardLock in tray.shown) { model.setTrayMenu(tray.setShown(TraySection.KeyboardLock, it)) }
            }
        }
    }
}

/** The small window that stays on top while the keyboard is locked. It never takes the focus. */
@Composable
fun KeyboardLockBadge(view: KeyboardLockView, onUnlock: () -> Unit, clock: () -> Long = System::currentTimeMillis) {
    val colors = Dysk.colors
    val now = lockClock(view, clock)
    Column(
        Modifier.width(360.dp).background(colors.panel, RoundedCornerShape(14.dp))
            .border(1.dp, colors.stroke, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DyskIcons.Keyboard, null, Modifier.padding(end = 10.dp), tint = colors.coral)
            Column(Modifier.weight(1f)) {
                Text("Keyboard locked", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(view.remainingText(now), color = colors.textSecondary, fontSize = 12.sp)
            }
            PrimaryButton("Unlock", onClick = onUnlock)
        }
        Spacer(Modifier.height(10.dp))
        Text("Or press ${KeyboardLockKeys.UNLOCK_LABEL}. The mouse and touchpad still work.", color = colors.textSecondary, fontSize = 11.5.sp)
    }
}
