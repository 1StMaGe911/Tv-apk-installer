#!/usr/bin/env python3
"""Open a saved-TV picker when Back is pressed on the main TV management screen."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
app_root = root / "vendor" / "cleanyourtv-app/app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
data = app_root.read_text(encoding="utf-8")
for import_line, anchor in (
    ("import androidx.compose.foundation.layout.Column", "import androidx.compose.foundation.layout.Box"),
    ("import androidx.compose.material3.AlertDialog", "import androidx.compose.material3.SnackbarHost"),
    ("import androidx.compose.material3.Text", "import androidx.compose.material3.SnackbarHost"),
    ("import androidx.compose.material3.TextButton", "import androidx.compose.material3.SnackbarHost"),
):
    if import_line not in data:
        data = data.replace(anchor, anchor + chr(10) + import_line, 1)

replacements = [
    (
        '    val scope = rememberCoroutineScope()\n',
        '    val scope = rememberCoroutineScope()\n    var showTvPicker by remember { mutableStateOf(false) }\n',
        "AppRoot state anchor",
    ),
    (
        '                    ManageScreen(state, m::dispatch)\n',
        '''                    BackHandler(enabled = !showTvPicker) { showTvPicker = true }
                    ManageScreen(state, m::dispatch)
                    if (showTvPicker) {
                        AlertDialog(
                            onDismissRequest = { showTvPicker = false },
                            title = { Text("Выбор телевизора") },
                            text = {
                                Column {
                                    state.cards.forEach { card ->
                                        TextButton(onClick = {
                                            showTvPicker = false
                                            m.dispatch(tv.cleanyour.core.flows.ManageIntent.Select(card.id))
                                        }) {
                                            Text(card.name)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTvPicker = false
                                    m.dispatch(tv.cleanyour.core.flows.ManageIntent.SetUpAnother)
                                }) { Text("Добавить телевизор") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTvPicker = false }) { Text("Закрыть") }
                            }
                        )
                    }
''',
        "Manage screen Back handler and TV picker",
    ),
]

for old, new, label in replacements:
    count = data.count(old)
    if count == 0 and label == "Manage screen Back handler and TV picker":
        import re
        match = re.search(r"(?m)^([ \\t]*)ManageScreen\\([^\\n]*\\)", data)
        if not match:
            raise SystemExit("Could not locate ManageScreen call for Back handler")
        indent = match.group(1)
        replacement = new.replace("                    ", indent)
        data = data[:match.start()] + replacement.rstrip() + data[match.end():]
    else:
        if count != 1:
            raise SystemExit(f"{label}: expected exactly one match, found {count}")
        data = data.replace(old, new, 1)

app_root.write_text(data, encoding="utf-8")
print("Back on the main TV screen now opens a saved-TV picker; selecting a TV switches to it.")
