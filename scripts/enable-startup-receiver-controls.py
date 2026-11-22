#!/usr/bin/env python3
"""Add guarded, reversible controls for user-installed startup receiver components."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"

def once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one integration anchor in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")

probe = upstream / "core/probe/src/main/kotlin/tv/cleanyour/core/probe/StartupReceivers.kt"
once(probe,
    "data class StartupReceiver(val component: String, val packageName: String, val lockedBoot: Boolean, val userInstalled: Boolean, val enabledState: StartupComponentState = StartupComponentState.UNKNOWN)",
    "data class StartupReceiver(val component: String, val packageName: String, val lockedBoot: Boolean, val userInstalled: Boolean, val enabledState: StartupComponentState = StartupComponentState.UNKNOWN, val protected: Boolean = false, val savedOriginalState: StartupComponentState? = null)")
once(probe,
    'if (name !in setOf("user", "boot", "locked") || name in sections)',
    'if (name !in setOf("user", "boot", "locked", "saved", "protected") || name in sections)')
once(probe,
    'if (!sections.keys.containsAll(listOf("user", "boot", "locked")))',
    'if (!sections.keys.containsAll(listOf("user", "boot", "locked", "saved", "protected")))')
once(probe,
    '        val receivers = linkedMapOf<String, StartupReceiver>()',
    '''        val savedStates = sections.getValue("saved").mapNotNull { line ->
            val parts = line.trim().split('|', limit = 2)
            if (parts.size != 2 || !component.matches(parts[0])) null
            else {
                val state = runCatching { StartupComponentState.valueOf(parts[1]) }.getOrNull()
                if (state == StartupComponentState.DEFAULT || state == StartupComponentState.ENABLED) parts[0] to state else null
            }
        }.toMap()
        val protectedPackages = sections.getValue("protected").mapNotNull { line ->
            val pkg = line.trim().substringBefore('/')
            pkg.takeIf { it.matches(Regex("[A-Za-z0-9_]+(?:\\\\.[A-Za-z0-9_]+)+")) }
        }.toSet() + setOf("android", "com.android.systemui", "ru.tvapk.installer")
        val receivers = linkedMapOf<String, StartupReceiver>()''')
once(probe,
    'receivers.putIfAbsent(value, StartupReceiver(value, pkg, locked, pkg in userPackages, componentState(pkg, value)))',
    'receivers.putIfAbsent(value, StartupReceiver(value, pkg, locked, pkg in userPackages, componentState(pkg, value), pkg in protectedPackages, savedStates[value]))')
once(probe,
    '''    companion object {
        internal val SCRIPT = ShellLine(''',
    '''    suspend fun setEnabled(session: TvSession, receiver: StartupReceiver, enabled: Boolean) {
        if (!receiver.userInstalled) throw IllegalArgumentException("System-app receivers are view-only")
        if (receiver.protected) throw IllegalArgumentException("This app provides a protected TV function")
        if (!Regex("^[A-Za-z0-9_]+(?:\\\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$").matches(receiver.component)) throw IllegalArgumentException("Invalid receiver component")
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(receiver.component.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        val file = "/data/local/tmp/cleanyourtv-startup-state/$key.state"
        val comp = receiver.component
        val command = if (!enabled) {
            if (receiver.enabledState != StartupComponentState.DEFAULT && receiver.enabledState != StartupComponentState.ENABLED) {
                throw IllegalStateException("Only a currently enabled receiver can be disabled")
            }
            if (receiver.savedOriginalState != null) throw IllegalStateException("An original state is already saved")
            "set -e; mkdir -p /data/local/tmp/cleanyourtv-startup-state; " +
                "if [ -e '$file' ]; then echo '__cyt_already_saved__'; exit 1; fi; " +
                "printf '%s|%s\\\\n' '$comp' '" + receiver.enabledState.name + "' > '$file.tmp'; " +
                "mv '$file.tmp' '$file'; " +
                "if ! pm disable-user --user 0 '$comp'; then rm -f '$file'; exit 1; fi; " +
                "echo '__cyt_changed__'"
        } else {
            if (receiver.savedOriginalState == null) throw IllegalStateException("No original state is saved for this receiver")
            if (receiver.enabledState != StartupComponentState.DISABLED) {
                throw IllegalStateException("Receiver is not disabled")
            }
            "set -e; [ -f '$file' ] || { echo '__cyt_no_backup__'; exit 1; }; " +
                "SAVED=$(cut -d'|' -f2 '$file'); " +
                "case \\"$SAVED\\" in " +
                "DEFAULT) pm default-state --user 0 '$comp' ;; " +
                "ENABLED) pm enable --user 0 '$comp' ;; " +
                "*) echo '__cyt_bad_backup__'; exit 1 ;; esac; " +
                "rm -f '$file'; echo '__cyt_changed__'"
        }
        val result = session.exec(
            ShellLine(command),
            ExecSpec(timeoutMs = 25_000, stdoutCap = 16 * 1024, retry = RetryClass.NEVER)
        )
        if (!result.stdout.contains("__cyt_changed__")) {
            throw IllegalStateException(result.stdout.ifBlank { "The TV did not confirm the component state change" })
        }
    }

    companion object {
        internal val SCRIPT = ShellLine(''')
once(probe,
    '''"echo '@@cyt-state@@' \\"\\$pkg\\"; dumpsys package \\"\\$pkg\\" 2>/dev/null | awk '/^[[:space:]]*enabledComponents:/ {p=1; print; next} /^[[:space:]]*disabledComponents:/ {p=2; print; next} /^[[:space:]]*[A-Za-z][A-Za-z0-9 _-]*:$/ {p=0} p {print}'; done"''',
    '''"echo '@@cyt-state@@' \\"\\$pkg\\"; dumpsys package \\"\\$pkg\\" 2>/dev/null | awk '/^[[:space:]]*enabledComponents:/ {p=1; print; next} /^[[:space:]]*disabledComponents:/ {p=2; print; next} /^[[:space:]]*[A-Za-z][A-Za-z0-9 _-]*:$/ {p=0} p {print}'; done; " +
                "echo '@@cyt-boot@@ saved'; for f in /data/local/tmp/cleanyourtv-startup-state/*.state; do [ -f \\"\\$f\\" ] && cat \\"\\$f\\"; done; " +
                "echo '@@cyt-boot@@ protected'; " +
                "{ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null; " +
                "settings get secure default_input_method 2>/dev/null; " +
                "settings get secure enabled_accessibility_services 2>/dev/null | tr ':' '\\\\n'; " +
                "dumpsys device_policy 2>/dev/null | grep -Eo '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+'; } | " +
                "sed 's#/.*##' | grep -E '^[A-Za-z0-9_]+(\\\\.[A-Za-z0-9_]+)+$' | sort -u"''')

machine = upstream / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/ManageMachine.kt"
once(machine,
    "    data object CheckStartupReceivers : ManageIntent",
    "    data object CheckStartupReceivers : ManageIntent\n    data class SetStartupReceiver(val component: String, val enabled: Boolean) : ManageIntent")
once(machine,
    "            ManageIntent.CheckStartupReceivers -> if (id != null && !before.startupReceiversLoading) scope.launch { checkStartupReceivers(id) }\n",
    "            ManageIntent.CheckStartupReceivers -> if (id != null && !before.startupReceiversLoading) scope.launch { checkStartupReceivers(id) }\n" +
    "            is ManageIntent.SetStartupReceiver -> if (id != null && !before.startupReceiversLoading) scope.launch { changeStartupReceiver(id, event.component, event.enabled) }\n")
once(machine,
    "    // The card's thumbnail is the last screenshot; tapping refreshes it when the TV is connected.\n",
    '''    private suspend fun changeStartupReceiver(id: TvId, component: String, enabled: Boolean) {
        send(StartupReceiversLoading)
        val session = d.access.live(id)
        if (session == null) {
            send(StartupReceiversFailed(id, "Нет активного ADB-сеанса с телевизором"))
            return
        }
        try {
            val receiver = state.value.startupReceivers?.receivers?.firstOrNull { it.component == component }
                ?: throw IllegalStateException("Компонент больше не найден. Обновите список.")
            d.startupReceiverReader.setEnabled(session, receiver, enabled)
            send(StartupReceiversLoaded(id, d.startupReceiverReader.read(session)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            send(StartupReceiversFailed(id, error.message ?: error.toString()))
        }
    }

    // The card's thumbnail is the last screenshot; tapping refreshes it when the TV is connected.
''')

screens = upstream / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"
once(screens,
    "    var startupDialogVisible by rememberSaveable(tv?.id?.value) { mutableStateOf(false) }\n",
    "    var startupDialogVisible by rememberSaveable(tv?.id?.value) { mutableStateOf(false) }\n" +
    "    var pendingStartupComponent by rememberSaveable(tv?.id?.value) { mutableStateOf<String?>(null) }\n" +
    "    var pendingStartupEnabled by rememberSaveable(tv?.id?.value) { mutableStateOf(false) }\n")
ui = screens.read_text(encoding="utf-8")
marker = "receiver.userInstalled"
position = ui.find(marker)
if position < 0:
    raise SystemExit("Could not find startup receiver row in ManageScreens.kt")
row_start = ui.rfind("Text(", 0, position)
row_end_marker = "Modifier.padding(top = 8.dp))"
row_end = ui.find(row_end_marker, position)
if row_start < 0 or row_end < 0:
    raise SystemExit("Could not find startup receiver text boundaries")
row_end += len(row_end_marker)
new_receiver = """Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text(buildString {
                                        append(if (receiver.userInstalled) stringResource(R.string.manage_startup_user_app) else stringResource(R.string.manage_startup_system_app))
                                        if (receiver.protected) append(" · " + stringResource(R.string.manage_startup_protected))
                                        append(" · " + receiver.packageName + " · " + (if (receiver.lockedBoot) stringResource(R.string.manage_startup_locked) else stringResource(R.string.manage_startup_boot)))
                                        append(10.toChar())
                                        append(receiver.component)
                                        append(10.toChar())
                                        append(when (receiver.enabledState) {
                                            StartupComponentState.DEFAULT -> "Состояние: по умолчанию"
                                            StartupComponentState.ENABLED -> "Состояние: включён"
                                            StartupComponentState.DISABLED -> "Состояние: отключён"
                                            StartupComponentState.UNKNOWN -> "Состояние: неизвестно"
                                        })
                                    })
                                    val canDisable = receiver.userInstalled && !receiver.protected &&
                                        (receiver.enabledState == StartupComponentState.DEFAULT || receiver.enabledState == StartupComponentState.ENABLED) &&
                                        receiver.savedOriginalState == null
                                    val canRestore = receiver.userInstalled && !receiver.protected &&
                                        receiver.enabledState == StartupComponentState.DISABLED && receiver.savedOriginalState != null
                                    if (canDisable || canRestore) {
                                        TextButton(
                                            enabled = !state.startupReceiversLoading,
                                            onClick = {
                                                pendingStartupComponent = receiver.component
                                                pendingStartupEnabled = canRestore
                                            }
                                        ) {
                                            Text(stringResource(if (canRestore) R.string.manage_startup_restore else R.string.manage_startup_disable))
                                        }
                                    }
                                }"""
ui = ui[:row_start] + new_receiver + ui[row_end:]
screens.write_text(ui, encoding="utf-8")

confirmation = '''        if (pendingStartupComponent != null) {
            AlertDialog(
                onDismissRequest = { pendingStartupComponent = null },
                title = { Text(stringResource(if (pendingStartupEnabled) R.string.manage_startup_confirm_restore else R.string.manage_startup_confirm_disable)) },
                text = { Text(stringResource(if (pendingStartupEnabled) R.string.manage_startup_restore_warning else R.string.manage_startup_disable_warning)) },
                confirmButton = {
                    TextButton(enabled = !state.startupReceiversLoading, onClick = {
                        val component = pendingStartupComponent
                        pendingStartupComponent = null
                        if (component != null) onIntent(ManageIntent.SetStartupReceiver(component, pendingStartupEnabled))
                    }) { Text(stringResource(if (pendingStartupEnabled) R.string.manage_startup_restore else R.string.manage_startup_disable)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingStartupComponent = null }) {
                        Text(stringResource(R.string.manage_startup_cancel))
                    }
                },
            )
        }
'''
once(screens,
    "        ScreenTitle(stringResource(R.string.manage_title), titleTop)\n",
    confirmation + "        ScreenTitle(stringResource(R.string.manage_title), titleTop)\n")

def merge(path: Path, entries: str) -> None:
    import re
    data = path.read_text(encoding="utf-8") if path.exists() else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    for name, value in re.findall(r'<string name="([^"]+)">([\s\S]*?)</string>', entries):
        if f'<string name="{name}">' not in data:
            data = data.replace("</resources>", f'    <string name="{name}">{value}</string>\n</resources>')
    path.write_text(data, encoding="utf-8")

merge(upstream / "feature/manage/src/main/res/values/strings.xml", """<string name="manage_startup_disable">Disable receiver</string>
<string name="manage_startup_restore">Restore original state</string>
<string name="manage_startup_protected">protected · view only</string>
<string name="manage_startup_confirm_disable">Disable this startup component?</string>
<string name="manage_startup_confirm_restore">Restore original component state?</string>
<string name="manage_startup_disable_warning">This can stop background features of the app. Only this receiver component is changed; the whole app is not disabled. You can restore its previous state here.</string>
<string name="manage_startup_restore_warning">The component will be returned to the exact state recorded before Clean Your TV disabled it.</string>
<string name="manage_startup_cancel">Cancel</string>""")
merge(upstream / "feature/manage/src/main/res/values-ru/strings.xml", """<string name="manage_startup_disable">Отключить компонент</string>
<string name="manage_startup_restore">Восстановить исходное состояние</string>
<string name="manage_startup_protected">защищён · только просмотр</string>
<string name="manage_startup_confirm_disable">Отключить этот компонент автозапуска?</string>
<string name="manage_startup_confirm_restore">Восстановить исходное состояние компонента?</string>
<string name="manage_startup_disable_warning">Это может нарушить фоновые функции приложения. Изменяется только этот компонент автозапуска, а не всё приложение. Исходное состояние можно восстановить здесь.</string>
<string name="manage_startup_restore_warning">Компонент будет возвращён в состояние, которое было записано до отключения Clean Your TV.</string>
<string name="manage_startup_cancel">Отмена</string>""")

print("Guarded startup receiver controls and exact-state restore integrated.")
