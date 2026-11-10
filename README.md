# FamilyCloud

Android-клиент WebDAV для роутера Keenetic: файловый менеджер и выгрузка фото и видео с телефона. Сейчас это каркас (этап M0): приложение открывается и показывает название. Подключение к серверу и синхронизация появятся на следующих этапах.

Интерфейс приложения на русском. Единственный сетевой адрес, к которому оно будет ходить, — WebDAV, который вы укажете сами.

## Сборка

Нужны JDK 17 и Android SDK. В CI это уже настроено: push в `main` и pull request запускают `.github/workflows/build.yml`. Локально:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug-APK локально: `app/build/outputs/apk/debug/app-debug.apk`.

Каждый push в `main` собирает APK на GitHub и обновляет страницу [Последняя сборка](https://github.com/alexeygodov/FamilyCloud/releases/tag/latest): `app-release.apk` (подписанный) и `app-debug.apk`. Тег `v*` публикует отдельный релиз с этими же файлами.

Репозиторий приложения — эта папка (`cloud-app/`), не родительский каталог. Workflow GitHub запускается только из корня репозитория. Ветка по умолчанию — `main`.

## Подпись release

Release-APK подписывается только если в GitHub Actions заданы секреты. Без них workflow собирает debug и не падает. Секреты не хранятся в репозитории: keystore, пароли и `keystore.properties` перечислены в `.gitignore`.

Потеря keystore означает, что обновление приложения поверх уже установленной release-сборки будет невозможно. Храните файл и пароли отдельно от репозитория.

Сгенерируйте keystore сами (пароли `keytool` спросит интерактивно, не записывайте их в команду):

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias familycloud
```

Закодируйте файл в одну строку base64.

Windows (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```

Linux / macOS:

```bash
base64 -w 0 release.jks
```

В настройках репозитория GitHub → Settings → Secrets and variables → Actions добавьте:

| Секрет | Значение |
|---|---|
| `KEYSTORE_BASE64` | строка base64 файла `release.jks` |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `KEY_ALIAS` | алиас ключа, в примере выше `familycloud` |
| `KEY_PASSWORD` | пароль ключа |

Для локальной release-сборки можно положить `keystore.properties` в корень проекта (файл не коммитится):

```properties
storeFile=C:\\path\\to\\release.jks
storePassword=<пароль хранилища>
keyAlias=familycloud
keyPassword=<пароль ключа>
```

Тег `v*` публикует отдельный GitHub Release. Если секретов подписи нет, release-APK не собирается и релиз не создаётся.
