#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
activity=root/"vendor/cleanyourtv-app/app/src/main/kotlin/tv/cleanyour/app/MainActivity.kt"
worker=root/"app/src/main/java/ru/tvapk/installer/UpdateCheckWorker.kt"
def replace_once(path,old,new):
    data=path.read_text(encoding="utf-8")
    if new in data:return
    if data.count(old)!=1:raise SystemExit(f"Expected one match in {path}: {old!r}; found {data.count(old)}")
    path.write_text(data.replace(old,new,1),encoding="utf-8")
replace_once(activity,"import tv.cleanyour.core.flows.resolveStart\n","import tv.cleanyour.core.flows.resolveStart\nimport ru.tvapk.installer.CleanYourTvUpdater\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.setValue\n")
replace_once(activity,"class MainActivity : ComponentActivity() {\n    private val graph","""class MainActivity : ComponentActivity() {
    private var openUpdateSettings by mutableStateOf(false)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("ru.tvapk.installer.OPEN_UPDATE_SETTINGS", false)) openUpdateSettings = true
    }
    private val graph""")
replace_once(activity,"""                LaunchedEffect(Unit) {
                    graph.tvs.load()
                    start = startKey(resolveStart(graph.tvs.tvs.value, graph.seams.journal))
                }""","""                var graphLoaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    graph.tvs.load()
                    graphLoaded = true
                }
                LaunchedEffect(graphLoaded, openUpdateSettings) {
                    if (graphLoaded) {
                        val fromNotification = openUpdateSettings ||
                            intent.getBooleanExtra("ru.tvapk.installer.OPEN_UPDATE_SETTINGS", false)
                        if (fromNotification) {
                            openUpdateSettings = false
                            intent.removeExtra("ru.tvapk.installer.OPEN_UPDATE_SETTINGS")
                            start = Dest.AppSettings
                            runCatching { CleanYourTvUpdater.download(this@MainActivity) }
                                .onFailure { android.util.Log.e("CleanYourTvUpdates", "Notification download failed", it) }
                        } else if (start == null) {
                            start = startKey(resolveStart(graph.tvs.tvs.value, graph.seams.journal))
                        }
                    }
                }""")
replace_once(worker,"flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK","flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP")
print("Notification routing fixed.")
