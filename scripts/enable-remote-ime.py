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

        <!-- ADB shell-only IPC: provider verifies the caller Binder UID. -->
        <provider
            android:name="tv.cleanyour.app.remote.RemoteInputProvider"
            android:authorities="${applicationId}.remoteinput"
            android:exported="true" />

'''
if "tv.cleanyour.app.remote.RemoteInputMethodService" not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Could not find manifest receiver insertion point")
    text = text.replace(anchor, addition + anchor, 1)
if "tv.cleanyour.app.remote.RemoteInputReceiver" not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Could not find update receiver insertion point")
    receiver = '''        <receiver
            android:name="tv.cleanyour.app.remote.RemoteInputReceiver"
            android:permission="android.permission.DUMP"
            android:exported="true">
            <intent-filter>
                <action android:name="tv.cleanyour.app.remote.PING" />
                <action android:name="tv.cleanyour.app.remote.COMMIT" />
            </intent-filter>
        </receiver>

'''
    text = text.replace(anchor, receiver + anchor, 1)
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
import android.os.Bundle
import android.os.Process
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

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

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

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
        fun isActive(): Boolean = active != null
    }
}

/**
 * ADB shell IPC via ContentProvider.call(). Binder preserves the caller UID on all
 * supported Android versions, unlike BroadcastReceiver.getSentFromUid() (API 34+).
 * The manifest additionally requires android.permission.DUMP, held by com.android.shell.
 */
class RemoteInputProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.SHELL_UID) { "Only the ADB shell may access remote input" }
        if (method == "ping") {
            return Bundle().apply { putBoolean("ready", true) }
        }
        check(method == "commit") { "Unsupported method" }
        val encoded = arg ?: throw IllegalArgumentException("Missing text payload")
        require(encoded.length <= 16384) { "Text payload is too large" }
        val value = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        require(value.isNotEmpty()) { "Text payload is empty" }
        check(RemoteInputMethodService.commit(value)) { "No active TV text field; focus an input field first" }
        return Bundle().apply { putBoolean("committed", true) }
    }
}

/** Explicit, shell-permission-protected IPC. This avoids ContentResolver authority lookup. */
class RemoteInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            when (intent?.action) {
                ACTION_PING -> {
                    setResultCode(android.app.Activity.RESULT_OK)
                    setResultData("ready=true")
                }
                ACTION_COMMIT -> {
                    val encoded = intent.getStringExtra("payload") ?: error("Missing text payload")
                    require(encoded.length <= 16384) { "Text payload is too large" }
                    val value = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
                    require(value.isNotEmpty()) { "Text payload is empty" }
                    check(RemoteInputMethodService.commit(value)) { "No active TV text field; focus an input field first" }
                    setResultCode(android.app.Activity.RESULT_OK)
                    setResultData("committed=true")
                }
                else -> error("Unsupported remote input action")
            }
        } catch (error: Throwable) {
            setResultCode(android.app.Activity.RESULT_CANCELED)
            setResultData("error=" + (error.message ?: "remote input failed"))
        }
    }

    companion object {
        const val ACTION_PING = "tv.cleanyour.app.remote.PING"
        const val ACTION_COMMIT = "tv.cleanyour.app.remote.COMMIT"
    }
}
''', encoding="utf-8")
print("Remote Unicode IME integration files generated.")
