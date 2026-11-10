package ru.tvapk.installer

internal object ApkInstallVerifier {
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun verify(
        packageName: String,
        expectedVersionCode: Long,
        installedPackages: Set<String>,
        installedVersionCode: Long?
    ): String? {
        if (!packagePattern.matches(packageName)) return "APK содержит некорректное имя пакета"
        if (expectedVersionCode <= 0L) return "Не удалось определить версию выбранного APK"
        if (packageName !in installedPackages) return "После установки пакет $packageName не найден на телевизоре"
        if (installedVersionCode == null) return "Не удалось проверить versionCode установленного пакета $packageName"
        if (installedVersionCode != expectedVersionCode) {
            return "Версия $packageName не совпадает: ожидалась $expectedVersionCode, установлена $installedVersionCode"
        }
        return null
    }
}
