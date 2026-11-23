#!/usr/bin/env python3
"""Wire automatic update checks into the primary Clean Your TV launcher."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"

def replace_once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")

application = upstream / "app/src/main/kotlin/tv/cleanyour/app/CytApplication.kt"
replace_once(
    application,
    "import tv.cleanyour.core.flows.planOrNull",
    "import tv.cleanyour.core.flows.planOrNull\nimport ru.tvapk.installer.schedulePeriodicUpdateCheck",
)
replace_once(
    application,
    "        super.onCreate()\n        initializeGraph()",
    """        super.onCreate()
        initializeGraph()
        // Check once on app startup and keep a 24-hour background check scheduled.
        runCatching { schedulePeriodicUpdateCheck(applicationContext) }
            .onFailure { android.util.Log.e("CleanYourTvUpdates", "Could not schedule update checks", it) }""",
)

activity = upstream / "app/src/main/kotlin/tv/cleanyour/app/MainActivity.kt"
replace_once(
    activity,
    "import android.content.Intent\nimport android.os.Bundle",
    "import android.Manifest\nimport android.content.pm.PackageManager\nimport android.os.Build\nimport android.content.Intent\nimport android.os.Bundle\nimport androidx.activity.result.contract.ActivityResultContracts",
)
replace_once(
    activity,
    "class MainActivity : ComponentActivity() {\n    private val graph",
    """class MainActivity : ComponentActivity() {
    private val updateNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The user may decline; update checks remain available in Settings. */ }

    private val graph""",
)
replace_once(
    activity,
    "        graph.credentialGate.attach(this)",
    """        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = getSharedPreferences("update_notification_permission", MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                updateNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        graph.credentialGate.attach(this)""",
)

main_activity = upstream / "app/src/main/kotlin/tv/cleanyour/app/MainActivity.kt"
replace_once(
    main_activity,
    "import tv.cleanyour.core.flows.resolveStart",
    "import tv.cleanyour.core.flows.resolveStart\\nimport ru.tvapk.installer.CleanYourTvUpdater",
)
replace_once(
    main_activity,
    "                    start = startKey(resolveStart(graph.tvs.tvs.value, graph.seams.journal))",
    \"\"\"                    val openUpdateSettings = intent.getBooleanExtra("ru.tvapk.installer.OPEN_UPDATE_SETTINGS", false)
                    if (openUpdateSettings) {
                        intent.removeExtra("ru.tvapk.installer.OPEN_UPDATE_SETTINGS")
                        start = Dest.AppSettings
                        CleanYourTvUpdater.download(this@MainActivity)
                    } else {
                        start = startKey(resolveStart(graph.tvs.tvs.value, graph.seams.journal))
                    }\"\"\",
)
print("Automatic update checks are wired into the primary app launcher.")
