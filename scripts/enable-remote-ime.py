#!/usr/bin/env python3
"""Add a shell-authenticated Android TV input method for Unicode text injection."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = root / "app/src/main/AndroidManifest.xml"
service = root / "app/src/main/java/tv/cleanyour/app/remote/RemoteInputMethodService.kt"
metadata = root / "app/src/main/res/xml/remote_input_method.xml"

text = manifest.read_text(encoding="utf-8")
anchor = '        <receiver\n            android:name="ru.tvapk.installer.CleanYourTvUpdateReceiver"'
addition = '''        <service
            android:name="tv.cleanyour.app.remote.RemoteInputMethodService"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_INPUT_METHOD"
            android:exported="true">
            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>
            <meta-data
                android:name="android.view.im"
                android:resource="@xml/remote_input_method" />
        </service>

        <!-- Only the Android shell (the already paired ADB peer) may submit remote text. -->
        <receiver
            android:name="tv.cleanyour.app.remote.RemoteInputReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="tv.cleanyour.app.REMOTE_INPUT_TEXT" />
            </intent-filter>
        </receiver>

'''
if "tv.cleanyour.app.remote.RemoteInputMethodService" not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Could not find manifest receiver insertion point")
    text = text.replace(anchor, addition + anchor, 1)
    manifest.write_text(text, encoding="utf-8")

metadata.parent.mkdir(parents=True, exist_ok=True)
metadata.write_text('''<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:supportsSwitchingToNextInputMethod="false">
    <subtype
        android:label="@string/app_name"
        android:imeSubtypeLocale="en_US"
        android:imeSubtypeMode="keyboard" />
</input-method>
''', encoding="utf-8")

service.parent.mkdir(parents=True, exist_ok=True)
service.write_text(r'''package tv.cleanyour.app.remote

import android.inputmethodservice.InputMethodService
import android.os.Binder
import android.os.Process
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A deliberately invisible IME. The phone sends UTF-8 text through the paired ADB shell;
 * commitText() lets Android deliver real Unicode to the currently focused TV text field.
 */
class RemoteInputMethodService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onCreateInputView(): View = FrameLayout(this).apply {
        layoutParams = FrameLayout.LayoutParams(1, 1)
    }

    fun commitRemoteText(value: String): Boolean {
        val connection: InputConnection = currentInputConnection ?: return false
        return connection.commitText(value, 1)
    }

    companion object {
        @Volatile private var active: RemoteInputMethodService? = null

        fun commit(value: String): Boolean = active?.commitRemoteText(value) ?: false
    }
}

/** Explicit ADB broadcast endpoint; rejects broadcasts not sent by Android's shell UID. */
class RemoteInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Binder.getCallingUid() != Process.SHELL_UID) return
        if (intent.action != "tv.cleanyour.app.REMOTE_INPUT_TEXT") return
        val encoded = intent.getStringExtra("payload") ?: return
        val value = runCatching {
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return
        if (value.isNotEmpty()) RemoteInputMethodService.commit(value)
    }
}
''', encoding="utf-8")
print("Remote Unicode IME integration files generated.")
