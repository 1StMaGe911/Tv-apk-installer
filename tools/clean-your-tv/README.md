# Сборка Clean Your TV без стартового экрана

Эта ветка не меняет основную ветку `main` и не затрагивает TV APK Installer.

## Что делает сборка

- берёт исходный русифицированный APK из `tools/clean-your-tv/input/`;
- переносит системный запуск с `WelcomeActivity` на `MainActivity`;
- задаёт имя приложения ровно `Clean Your TV`;
- удаляет папку с QR-кодом и ссылкой на донат `assets/rusmod`;
- сохраняет остальные ресурсы и код приложения;
- пересобирает и подписывает APK;
- публикует файл в GitHub Actions → Artifacts.

## Как запустить

1. Откройте ветку `cleanyourtv-build` в GitHub.
2. Нажмите **Add file → Upload files**.
3. Загрузите один файл `cleanyourtv-0.4.8-rusmod-pro100tehnika-beta2.apk` в папку `tools/clean-your-tv/input/`.
4. Нажмите **Commit changes** именно в ветку `cleanyourtv-build`.
5. Откройте вкладку **Actions**, дождитесь успешного задания **Clean Your TV — убрать стартовый экран**.
6. Скачайте APK из раздела **Artifacts**.

Важно: APK будет подписан новым временным ключом GitHub Actions. Android может потребовать удалить предыдущую модифицированную версию перед установкой новой. Данные приложения при удалении могут потеряться.

Сборка намеренно останавливается, если не находит ожидаемые WelcomeActivity и MainActivity или не может перенести launcher-фильтр.
