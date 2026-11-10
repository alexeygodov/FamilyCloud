# Ревью реализации FamilyCloud (коммит `49249dc`)

Ревью по коду и `SPEC.md`. Это список правок для следующего агента: идти по порядку приоритетов.

## Что проверено и чем

| Проверка | Результат |
|---|---|
| `./gradlew testDebugUnitTest` локально | Проходит (JDK 17, Android SDK есть). |
| Временный тест-зонд (удалён, дерево чистое) | Подтвердил дефекты C1 и M7 ниже. |
| Чтение кода всех модулей | Остальные пункты — по коду, на устройстве не проверялись. |
| Телефон, роутер Keenetic, статус CI на GitHub | **Не проверялось** (нет устройства, `gh` не установлен). Пункты с пометкой «проверить на устройстве» требуют ручной проверки. |

## Что сделано хорошо

- Свой WebDAV-клиент на OkHttp, без лишних библиотек. Стек и структура соответствуют `SPEC.md`.
- Загрузка потоком из `ContentResolver` (без чтения файла в память), `.part` и затем `MOVE`.
- Пароль в `EncryptedSharedPreferences`, `toString()` у `SavedConnection` его не печатает, `allowBackup=false`, в debug-логе только метод и код.
- Подпись release из секретов с безопасным откатом на debug, минимальные `permissions` у workflow.
- Находка про HTTP/1.1 для Keenetic учтена в клиенте.
- Парсер PROPFIND и построение URL покрыты тестами, зависимости через version catalog.

---

## Критично (чинить первыми)

### C1. Во вложенных папках пути файлов неверные — можно удалить или переместить не тот файл
Проверено тестом. `WebDavClient.propfind` передаёт в `PropfindParser.parse` адрес **листаемой папки**,
а `UrlBuilder.relativeOf` считает путь от него. Для `/webdav/Photos/` дети получаются как `a.jpg` и `Sub/`
вместо `Photos/a.jpg` и `Photos/Sub/`.

Последствия: переход в папку второго уровня открывает `/webdav/Sub/` в корне; удаление, переименование,
перемещение, скачивание и просмотр во вложенной папке работают с **файлом из корня** с тем же именем
(`DELETE` вернёт 204, и пользователь потеряет не тот файл).

Правка:
- [x] Парсить `href` относительно **корня** WebDAV (базового URL из настроек), а не URL запроса. Самую запрошенную папку отбрасывать сравнением нормализованных путей, а не по пустому остатку.
- [x] Передавать базовый URL в `WebDavClient.propfind` или возвращать `RemoteEntry` с путём от корня, который строит `WebDavRepository`.
- [x] Тесты: листинг `/webdav/Photos/` даёт `Photos/a.jpg`, `Photos/Sub/`; запись самой папки отбрасывается; кириллица и пробелы в подпапке.
- [ ] Ручная проверка на роутере: вход на 3 уровня вглубь, удаление, перемещение там.

### C2. Прерванная или неудавшаяся выгрузка навсегда помечается как выгруженная
`SyncRepository.uploadMedia` пишет строку в `uploaded` **до** `PUT` (с `partPath`). При сбое (обрыв сети, 507,
убитый процесс) строка остаётся. На следующем запуске `cleanupParts` удаляет `.part` и вызывает `clearPart`,
то есть обнуляет `partPath`, но оставляет `size`/`dateModified`. Дальше `needsUpload` возвращает `false`, и файл
**никогда не выгружается**. Это ломает критерий приёмки 6 и основной сценарий.

Правка:
- [x] В `cleanupParts` после удаления `.part` **удалять строку** `uploaded` (или хранить частичные загрузки в отдельной таблице `partial_upload`), а не очищать `partPath`.
- [x] Строку «выгружено» писать только после успешного `MOVE`.
- [ ] Тест на `SyncRepository` с фейковым клиентом: обрыв посреди выгрузки → следующий запуск снова выгружает файл; `.part` удалён; дублей нет. Покрыт только предикат `needsUpload(..., incomplete = true)` в `RemoteNamesTest` (Room на JVM без Robolectric не поднимается).

### C3. Скачивание и «открыть» читают сеть в главном потоке
`LocalFiles.saveToDownloads` и `cacheForView` вызываются из `viewModelScope.launch` (главный поток). `client.open`
возвращает управление на главный поток, и `stream.stream.copyTo(...)` читает сокет там. Ожидаемо
`NetworkOnMainThreadException`, которое ловит `catch (_: Exception)` и показывает «ошибка сети».
Проверить на устройстве: скачать файл больше нескольких КБ.

Правка:
- [x] Весь цикл копирования обернуть в `withContext(Dispatchers.IO)` внутри `LocalFiles` (диспетчер инжектировать).
- [x] Не глотать `CancellationException`: пробрасывать и удалять незавершённую загрузку. Ошибка записи на диск по-прежнему показывается как «ошибка сети».
- [x] Прогресс и отмена для больших файлов (хотя бы индикатор).

### C4. Фоновые воркеры вызывают `setForeground` из фона — на Android 12+ это падает
`SyncWorker`, `MediaWatchWorker` (и `UploadWorker`, если запущен не из UI) безусловно вызывают
`setForeground(...)` в начале `doWork`. Периодические и запускаемые триггером работы не могут быть expedited,
а запуск foreground service из фона на Android 12+ запрещён (`ForegroundServiceStartNotAllowedException`).
Ожидаемо: автосинхронизация при закрытом приложении (критерий 5) не работает. Проверить на устройстве с Android 12+.

Правка:
- [x] Обернуть `setForeground` в `try/catch` (`IllegalStateException`) и продолжать работу без уведомления.
- [x] Для фоновых запусков не поднимать foreground вообще; foreground — только для `syncNow` и загрузок из UI (expedited).
- [x] Лимит работы воркера ~10 минут: большой файл на медленном аплинке будет обрываться и начинаться заново (докачки нет). Решить явно: либо foreground-сервис по запросу пользователя, либо документированное ограничение; тест 1 ГБ видео на реальной сети. Ограничение записано в `docs/SERVER_NOTES.md`. Проверка 1 ГБ на устройстве не делалась.

---

## Высокий приоритет

### H1. Запросы к MediaStore на главном потоке
`SyncViewModel.reload()` → `SyncRepository.pendingCount()` → `MediaScanner.files()` и `SyncViewModel.loadAlbums()` →
`MediaScanner.albums()` выполняются в `viewModelScope` (Main). `albums()` перебирает **всю** медиатеку, чтобы собрать
бакеты. На 50 тысячах фото это ANR.
- [x] `withContext(Dispatchers.IO)` для `pendingCount`, `files`, `albums` (внутри репозитория, не в UI).
- [x] `albums()` через `GROUP BY`/`DISTINCT` по `bucket_id` (на API 26+ — запрос с проекцией только бакета и без полного обхода, либо кэш). Проекция только `bucket_id` и имени; `GROUP BY` у MediaStore нет.

### H2. Один «плохой» файл блокирует всю пару навсегда
`SyncRepository.syncPair` при «временной» ошибке делает `return error` и прекращает пару. Тип ошибки определяется
**сравнением локализованных строк** (`isTransientFailure`), а `WebDavClient.call` маппит любой `IOException`
(в том числе ошибку чтения локального файла, несовпадение `Content-Length`, `http-disabled`) в `Network`.
Если `MediaStore.SIZE` не совпадает с реальным размером (бывает 0 или устаревший), `OkHttp` бросает `IOException`,
ошибка считается временной, и **все следующие файлы пары не выгружаются**.
- [x] Типизированные ошибки вместо строк: `sealed interface SyncFailure { Transient, Permanent, LocalIo }`. В `SyncPairEntity.lastError` хранить код/ключ, текст собирать в UI. Повтор решает `WebDavError.retries()` (сеть, таймаут, SSL, HTTP 5xx). В паре по-прежнему лежит уже собранный текст.
- [x] Размер брать из `ParcelFileDescriptor.statSize`/`AssetFileDescriptor.length`, а не из `MediaStore.SIZE`. Если открыть файл нельзя — это ошибка файла, а не сети: пропустить файл и идти дальше.
- [x] Ошибка одного файла не должна останавливать остальные; общий счётчик «не удалось: N».
- [x] Не повторять 3 раза и не ждать на 401/403/507/409 (повторять только сеть и 5xx).

### H3. «Отмена» и остановка воркера не прерывают передачу
`WebDavClient` использует блокирующий `execute()` внутри `withContext(IO)`. Отмена корутины не вызывает `Call.cancel()`.
`TransferControl.cancel()` проверяется только **между файлами**, так что кнопка «Отмена» в уведомлении во время
выгрузки большого видео ничего не делает до конца файла. То же при остановке WorkManager (потеря Wi-Fi).
- [x] Выполнять вызов через `suspendCancellableCoroutine` с `invokeOnCancellation { call.cancel() }`.
- [x] Проверять отмену внутри `StreamingBody.writeTo`. `writeTo` смотрит `Call.isCanceled()` и прерывает запись.
- [x] Заменить глобальный счётчик `TransferControl` на отмену через `WorkManager.cancelWorkById`. Счётчик оставлен как запасной стоп между файлами; кнопка уведомления отменяет работу по id.
- [x] `StreamingBody`: переопределить `isOneShot() = true`.

### H4. Фото теряют геометки при выгрузке
Запрошен `ACCESS_MEDIA_LOCATION`, но файл читается через `openInputStream(file.uri)` без
`MediaStore.setRequireOriginal(uri)`. На Android 10+ GPS из EXIF вырезается. Для резервной копии фото это потеря данных.
- [x] Использовать `MediaStore.setRequireOriginal` при наличии разрешения; если разрешения нет, показать предупреждение.
- [ ] Проверить на устройстве: сравнить EXIF на сервере и на телефоне.

### H5. Нечёткое определение «файл уже есть»
`decideRemoteName` сравнивает имена из `RemoteEntry.name`, а это `displayname` (или имя из `href`). Если `displayname`
сервера отличается от реального имени, дубли и лишние `(1)` гарантированы. Имя при переименовании и перемещении
(`BrowserViewModel.destination`, `onFolderPicked`) тоже берётся из `entry.name`.
- [x] Использовать имя из `href` (`UrlBuilder.fileName(relativePath)`), `displayname` — только для показа.
- [x] Сравнение имён без учёта регистра, если файловая система на диске роутера нечувствительна к регистру (NTFS/FAT). Проверить на роутере. Сравнение без учёта регистра есть в коде; на роутере не проверялось.

---

## Средний приоритет

### M1. Пароль виден на экране
`ConnectionScreen`: поле пароля без `PasswordVisualTransformation` и без `KeyboardType.Password`. Добавить, плюс кнопку «показать».
Поле URL: `KeyboardType.Uri`, `autoCorrect = false`.

### M2. Состояние теряется при повороте
`ConnectionScreen` и диалоги `BrowserScreen` (`newFolder`, `renameEntry`, `deleteEntry`, тексты) на `remember`.
Спецификация требует поворот без потери состояния: `rememberSaveable`.

### M3. Гонки при листинге
`BrowserViewModel.refresh()` запускает новую корутину без отмены предыдущей: быстрый переход между папками показывает
результат не той папки. Старый список остаётся на экране при ошибке или во время загрузки (можно нажать по записи из
предыдущей папки).
- [x] Хранить `Job`, отменять предыдущий; очищать список при смене папки; показывать ошибку на экране с «Повторить» (по `SPEC.md`), а не только snackbar.

### M4. Тестовое подключение подменяет учётные данные для всего приложения
`WebDavRepository.test` ставит глобальный `attempt` в `SettingsRepository`, который `snapshot()` отдаёт всем запросам,
включая фоновые воркеры. Пока идёт проверка, воркер ходит с учётными данными из формы.
- [x] Использовать отдельный `OkHttpClient`/запрос с явными учётными данными для проверки, без глобального состояния.

### M5. Недетерминированное «ожидание» в `SyncRepository`
Общий `Mutex` блокирует загрузки из UI, пока идёт долгая синхронизация альбомов (и наоборот), а `UploadWorker`
может упереться в лимит времени воркера. Разделить блокировки или поставить задания в одну очередь.

### M6. Триггер по медиа слишком чувствителен
`MediaWatchWorker` срабатывает на любое изменение медиатеки и запускает полную синхронизацию всех пар (с миганием
уведомления). Нужна задержка (`setTriggerContentUpdateDelay`, `setTriggerMaxContentDelay`), чтобы собирать серию снимков,
и пропуск при отсутствии изменений в выбранных альбомах.

### M7. Эмодзи и символы вне BMP в именах портят URL
Проверено тестом: `UrlBuilder.resolve(base, "😀 фото.jpg")` даёт `%3F%3F%20...`. `encodeSegment` и `lenient`
кодируют по `Char`, суррогатные пары превращаются в `?`. Файл с эмодзи в имени (частый случай для папок) не откроется
и не выгрузится под своим именем.
- [x] Кодировать по code point (`codePoints()`), либо через `HttpUrl.Builder.addPathSegment`.
- [x] Тест с эмодзи, кириллицей, `#`, `%`, `+`, пробелом, `..`.
- [x] Валидация имён при создании и переименовании: не пустое, без `/`, не `.` и не `..`.

### M8. Скачивание
- [x] «Скачать» доступно для папок (GET на коллекцию упадёт): скрыть или реализовать.
- [x] Для API < 29 нет `WRITE_EXTERNAL_STORAGE` (maxSdk 28) в манифесте, а запись в `Downloads` его требует.
- [x] Неполный файл в `Downloads` после обрыва: удалять запись при ошибке (сейчас остаётся `IS_PENDING=1`).
- [x] Кэш `cacheDir/open` не очищается и имена могут конфликтовать (два файла `IMG_1.jpg` из разных папок): уникальный подкаталог и очистка.

### M9. Запрос разрешений при каждом входе на экран синхронизации
`LaunchedEffect(Unit) { launcher.launch(...) }` показывает диалог каждый раз, а при постоянном отказе молчит.
Запрашивать при добавлении первой пары и показывать объяснение с кнопкой в настройки приложения.

### M10. Сообщение об ошибке при выключенном HTTP
`AuthInterceptor` бросает `IOException("http-disabled")`, клиент превращает это в «Ошибка сети». Нужна отдельная
ошибка «Адрес http запрещён настройкой». Заодно `network_security_config.xml` с `cleartextTrafficPermitted="true"`
глобально: разрешение нужно сузить, если возможно (например, только для введённого пользователем хоста).

### M11. Воспроизведение видео
`VideoViewModel` держит плеер во `ViewModel` без привязки к жизненному циклу экрана: уход приложения в фон не ставит
паузу, звук продолжает играть. Паузу в `onStop`, освобождение в `onCleared` оставить.

---

## Низкий приоритет и качество кода

- **Нет схемы БД и миграций.** `exportSchema=false`, версия 1. Включить экспорт схемы и миграции до первого релиза с данными пользователей.
- **`security-crypto` 1.1.0 помечена устаревшей.** `createSecurePrefs` без `try/catch`: повреждённый keystore (бывает после восстановления устройства) роняет приложение при запуске. Добавить обработку и запрос повторного ввода пароля.
- **Дублирование.** В `BrowserScreen` блок «открыть через внешнее приложение» повторён дважды, три воркера почти идентичны, `uploadMedia` и `uploadDocument` содержат один и тот же цикл повторов. Вынести.
- **Файлы слишком большие.** `Screens.kt` ~600 строк, `ViewModels.kt` ~380: разнести по экранам (`ui/browser`, `ui/sync`, `ui/settings`), как в `SPEC.md` раздел 5.
- **Строки в коде.** В `Screens.kt` `formatSize` содержит «Б», «КБ», «МБ», «ГБ» — нарушение правила «все строки в `strings.xml`».
- **Логика в Composable.** `BrowserScreen` строит `Intent` и показывает ошибку через `viewModel.message.value`. Перенести в ViewModel и события.
- **`BasicAuthenticator`** фактически мёртвый код: заголовок и так отправляется превентивно.
- **Один `delay` после последней попытки** (лишние 4 секунды).
- **`PROPFIND` при каждой выгрузке документа** (`uploadDocument`) и целиком всей папки через `body.string()`: на папке с десятками тысяч файлов это память и время. Рассмотреть потоковый разбор.
- **`.part` называется `имя.part.ext`**, а не `имя.part`, как в `SPEC.md`. Отклонение допустимо, но тогда зафиксировать в `SPEC.md`/`SERVER_NOTES.md`.

## Расхождения с `SPEC.md`

| Требование | Состояние |
|---|---|
| 3.2 Сортировка (имя/дата/размер, обе стороны) | Только по имени, без выбора |
| 3.2 Мультивыбор, действия над несколькими файлами | Нет, только меню одной записи |
| 3.2 Pull-to-refresh, хлебные крошки | Есть кнопка «обновить», заголовок — просто путь |
| 3.2 Превью картинок в списке | Нет, только иконки; миниатюры не реализованы |
| 3.2 Свайп по картинкам папки | Нет, открывается одна картинка; нет ограничения панорамирования |
| 3.2 Выбор папки: «можно создать папку» | Кнопка создания папки есть и в режиме выбора |
| 3.2 Ошибки с «Повторить» | На экране файлов ошибка и кнопка «Повторить» |
| 3.3 Статус «последняя синхронизация» | Показывается на карточке пары |
| 3.3 Прогресс загрузки (по файлу) | Только счётчик «N из M», внутри файла прогресса нет (`onProgress` пустой) |
| 4 Поворот без потери состояния | Поля подключения и диалоги на `rememberSaveable` |
| 6 Тесты: логика «что выгружать» на фейках, тесты `MOVE/COPY/MKCOL/DELETE` | `RemoteNamesTest` и `WebDavClientTest` покрывают имена и методы; фейка `SyncRepository` нет |
| 8/SERVER_NOTES | HTTP/1.1, имя `.part` и лимит 10 минут записаны. Поведение `MOVE` на роутере не проверялось |

## CI и релизы

- [x] **Тег `latest` не двигается.** `softprops/action-gh-release` не переносит существующий тег; `target_commitish` действует только при создании. Страница «Последняя сборка» с новыми APK будет ссылаться на старый коммит. Либо пересоздавать тег шагом `git tag -f` + push (нужен `contents: write`), либо публиковать только по тегам `v*`. В workflow добавлен `git tag -f latest` после публикации с `main`.
- [x] **`versionCode = 4` вручную.** Все сборки из `main` имеют одинаковый `versionCode`. Задавать из `GITHUB_RUN_NUMBER` (или из тега). Локально пол `5`, в CI — номер прогона, если он больше.
- [x] **Debug и release с одним `applicationId`** не ставятся вместе. Добавить `applicationIdSuffix = ".debug"` для debug.
- [x] **В CI нет `lintDebug`.** `lintDebug` добавлен в workflow и локально проходит. Запуск release-APK на телефоне по-прежнему не проверялся.
- [ ] **В публичный Release попадает debug-APK.** Если репозиторий публичный, убрать debug из релиза, оставить в артефактах. Оставлен: на странице релиза нужны оба APK.
- [ ] Проверить, что версии действий в `build.yml` (`checkout@v6`, `upload-artifact@v7`, `download-artifact@v8`, `action-gh-release@v3`, `setup-gradle@v6`) существуют: я их не проверял, CI на GitHub не открывал.

## Порядок работы

1. C1, C2 (с тестами) — выпустить сборку. Без них приложением пользоваться нельзя.
2. C3, C4, H1 — после ручной проверки на телефоне (скачивание, автосинхронизация при закрытом приложении, большая библиотека).
3. H2, H3, H4, H5.
4. Средний приоритет и расхождения со `SPEC.md`.
5. Низкий приоритет, CI, документация. В `docs/SERVER_NOTES.md` записать фактические находки по Keenetic (HTTP/1.1, `displayname`, регистр имён, лимит размера, поведение `MOVE`/`COPY`).

Ручная проверка на устройстве после пунктов 1–3: вход в папку 3 уровня и удаление там; выгрузка, оборванная выключением Wi-Fi, затем повтор; скачивание файла 50 МБ; автосинхронизация при закрытом приложении на Android 12+; GPS в EXIF на сервере; отмена посреди большого видео.

---

# Повторное ревью (незакоммиченные правки)

Проверено: `testDebugUnitTest`, `assembleDebug`, `assembleRelease` проходят. `lintDebug` **падает** (11 ошибок). Правки не закоммичены и не запушены, CI на них не запускался. Каталог `app/schemas/` не добавлен в git.

C1, H1 (частично), H3, H5, M1–M4, M7, M10, M11 и CI-пункты по сути исправлены. Остались и появились новые пункты.

## Блокирующие

- [x] **R1. Крэш на Android 8–12.** `UrlBuilder.percent` вызывает `URLEncoder.encode(String, Charset)`, а `decodePath` — `URLDecoder.decode(String, Charset)`. Оба метода есть только с API 33, minSdk = 26. На Android ≤12 любой путь с пробелом, кириллицей или эмодзи даёт `NoSuchMethodError` (это `Error`, `catch (Exception)` его не ловит). Юнит-тесты на JVM этого не видят, ловит `lintDebug`. Заменить на `URLEncoder.encode(text, "UTF-8")` / `URLDecoder.decode(text, "UTF-8")`.
- [x] **R2. `lintDebug` красный.** `./gradlew lintDebug` проходит. Шаг добавлен в CI. Исправить остальные ошибки: `@OptIn(UnstableApi::class)` для Media3 в `VideoViewModel`; `NonObservableLocale` (`Screens.kt`, `details` и карточка пары — получать `Locale` через `LocalConfiguration`); `LocalContextGetResourceValueCall` (`Screens.kt`, вместо `context.getString` в Composable брать `stringResource`). Ошибку по `local.properties` (экранирование `\` в пути SDK) исправить в самом файле или игнорировать: он не коммитится. После этого добавить `lintDebug` в CI.
- [x] **R3. Регресс: HTTP при первом подключении.** `WebDavRepository.test` больше не ставит «попытку», а `AuthInterceptor` проверяет `saved?.allowHttp`. Если настроек ещё нет (или сохранён https), `http://` блокируется даже с включённым флажком. Передавать `allowHttp` в `WebDavClient.authenticated(...)` и проверять его там.
- [ ] **R4. Закоммитить и запушить**, дождаться зелёного CI. Добавить `app/schemas/` в git.

## Важные

- [x] **R5. Тест на C2 ничего не проверяет.** Логика вынесена в `UploadEngine`, сценарии покрыты `UploadEngineTest`. Отмена `propfind` покрыта `WebDavClientTest`. `interruptedUploadIsNotTreatedAsFinished` проверяет `needsUpload(incomplete = true)`, это работало и до правки. Сам баг был в `cleanupParts` (`clearPart`). Нужен тест `SyncRepository` с фейковым `WebDavClient` и in-memory Room: сбой посреди выгрузки → следующий запуск снова выгружает файл. Отмена запроса (`Request.await`) тоже без теста.
- [x] **R6. Фоновая выгрузка большого видео не завершится.** `setForeground` пробуется, когда есть файл. Проверка 1 ГБ на телефоне не делалась. Теперь `setForeground` вызывается только при ручном запуске (`bypass`). Периодический воркер ограничен ~10 минутами, а докачки нет: видео, которое не успевает за 10 минут, начинается заново каждый раз (1 ГБ при 5 Мбит/с — ~27 минут). Приложения из белого списка батареи (приложение это просит) освобождены от запрета запуска foreground-сервиса из фона. Поэтому пробовать `setForeground` и в фоне (`try/catch IllegalStateException` уже есть), а не отключать его совсем.
- [x] **R7. `.part` может остаться на сервере.** Строка удаляется только после успешного `DELETE`. Для документов неудалённый `.part` пишется в `OrphanPart` (миграция 1→2). В `uploadMedia` при сбое строка `uploaded` удаляется **независимо от того, удалился ли `.part`**. Если сети нет, `client.delete(partUrl)` не сработает, а строки для будущей очистки уже нет. Строку удалять только после успешного `DELETE` (она и так считается незавершённой по `partPath != null`).
- [x] **R8. Обрыв сети посреди прогона.** Пара останавливается после трёх файлов подряд с сетевой ошибкой. Раньше пара останавливалась, теперь цикл идёт по всем файлам: каждый ждёт 3 попытки с паузами. Тысяча файлов без сети — десятки минут впустую. Прерывать пару после нескольких подряд сетевых ошибок.
- [x] **R9. Кнопка «Отмена».** `requestCode` — хеш работы. `TransferControl` убран. Кнопка только у «Синхронизировать сейчас» и выгрузки из интерфейса. `PendingIntent` для отмены у всех уведомлений с `requestCode = 7`; extras при сравнении `Intent` не учитываются, а флаг `UPDATE_CURRENT` перезаписывает их. Если одновременно идут синхронизация и загрузка, «Отмена» может отменить не ту работу. Использовать `workId.hashCode()` как `requestCode`.
- [x] **R10. `MediaStore.setRequireOriginal` для документов.** Только для MediaStore, остальные исключения тоже ловятся. `openOriginal` теперь вызывается и для URI из `OpenMultipleDocuments` (`uploadDocument`). Для не-MediaStore URI поведение метода не гарантировано, ловится только `SecurityException`. Использовать `setRequireOriginal` только для URI из `MediaScanner`, ловить `Exception`.
- [x] **R11. Типизированные ошибки (H2) сделаны наполовину.** `SyncFailure` и код в `lastError`. `isTransientFailure` удалена. Воркер повторяет работу по `retryable`. `UploadProblem` есть, но `runUploads`, `SyncWorker` и `lastError` по-прежнему сравнивают локализованные строки (`isTransientFailure`).

## Мелочи

- [x] `refresh()` обнуляет список только при смене папки.
- [x] У скачивания отдельный индикатор `transferring`.
- [x] Пароль хранится в `remember`, не в `rememberSaveable`.
- [x] `albums()` оставлен обходом бакетов вне главного потока. `GROUP BY` не используется.
- [x] `decideRemoteName` ищет имя в карте нижнего регистра.

---

# Как поправить (инструкция исполнителю)

Порядок: R1 → R3 → R2 → R5 + R7 + R8 → R9 → R6 → R10 → R11 → мелочи → R4 (коммит). После каждого блока: `./gradlew testDebugUnitTest lintDebug assembleDebug`. Новых функций не добавлять.

## R1. API 33 в `UrlBuilder` (крэш на Android 8–12)

Файл `data/webdav/UrlBuilder.kt`. Использовать перегрузки с именем кодировки (они есть с API 1):

```kotlin
private fun percent(text: String): String =
    URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private fun decodePath(encoded: String): String {
    if (encoded.isEmpty()) return ""
    return encoded.split('/').joinToString("/") { segment ->
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    }
}
```

Убрать неиспользуемый импорт `StandardCharsets`. Затем найти другие вызовы, которых нет на API 26: `Select-String -Path app\src\main -Pattern "Charset|StandardCharsets" -Recurse`. `Credentials.basic(..., StandardCharsets.UTF_8)` в OkHttp допустим. Проверка — только `lintDebug` (юнит-тесты на JVM эту ошибку не ловят), поэтому R2 обязателен.

## R2. Ошибки `lintDebug`

1. **Media3 (`ViewModels.kt`, `VideoViewModel`).** Добавить `import androidx.media3.common.util.UnstableApi` и `@OptIn(UnstableApi::class)` на класс `VideoViewModel`. Если lint всё равно ругается, использовать `@androidx.annotation.OptIn(markerClass = [UnstableApi::class])`.
2. **`NonObservableLocale` (`Screens.kt`: `details` и карточка пары).** Не вызывать `Locale.getDefault()` в Composable. Взять `val locale = LocalConfiguration.current.locales[0]` и передать в форматтер: `DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale)`. Вынести в одну функцию `formatDate(epochMs: Long, locale: Locale)` и использовать в обоих местах.
3. **`LocalContextGetResourceValueCall` (`Screens.kt`, `BrowserScreen`, блоки «открыть через внешнее приложение»).** Два одинаковых блока заменить одной функцией и убрать `context.getString` из Composable:

```kotlin
// BrowserViewModel
fun openExternally(entry: RemoteEntry, start: (Uri, String) -> Boolean) {
    openCached(entry) { uri, mime ->
        if (!start(uri, mime)) {
            message.value = getApplication<Application>().getString(R.string.error_open_external)
        }
    }
}

// Screens.kt
private fun tryView(context: Context, uri: Uri, mime: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
```

В обоих местах вызова: `viewModel.openExternally(entry) { uri, mime -> tryView(context, uri, mime) }`.
4. **`local.properties`.** Экранировать путь SDK: `sdk.dir=C\:\\Users\\godov.a.a\\AppData\\Local\\Android\\Sdk`. Файл не коммитится.
5. Добавить в `.github/workflows/build.yml` перед сборкой: `./gradlew lintDebug`. Предупреждения (`Warning`) CI не валят, только `Error`. Если что-то из ошибок нельзя исправить, добавить в `app/build.gradle.kts` `lint { baseline = file("lint-baseline.xml") }` и закоммитить baseline, но не отключать проверки целиком.

## R3. HTTP при первом подключении

Сейчас `WebDavClient.authenticated()` добавляет интерцептор к клиенту, в цепочке которого остаются глобальные `AuthInterceptor` (запрещает `http` по сохранённым настройкам) и `BasicAuthenticator`. Сделать отдельный клиент для проверки без глобальных интерцепторов:

```kotlin
fun authenticated(login: String, password: String, allowHttp: Boolean): WebDavClient {
    val header = Credentials.basic(login, password, StandardCharsets.UTF_8)
    val builder = http.newBuilder()
    builder.interceptors().clear()
    val client = builder
        .authenticator(Authenticator.NONE)
        .addInterceptor { chain ->
            if (chain.request().url.scheme == "http" && !allowHttp) throw HttpDisabledException()
            chain.proceed(chain.request().newBuilder().header("Authorization", header).build())
        }
        .build()
    return WebDavClient(client)
}
```

В `WebDavRepository.test`: `client.authenticated(login, password, allowHttp).propfind(normalized, depth = 0)`.

Тесты (`WebDavClientTest`, `MockWebServer` отдаёт `http://`):
- `authenticated("u", "p", allowHttp = true)` к `server.url("/webdav/")` → `Ok`, в запросе заголовок `Authorization: Basic ...`.
- `allowHttp = false` → `Err(WebDavError.HttpDisabled)`.
- Клиент по умолчанию, созданный как `WebDavClient(OkHttpClient())`, не должен менять поведение.

## R4. Коммит и CI

1. `git status`: убедиться, что нет `*.jks`, `keystore.properties`, `local.properties`, `build/`.
2. `git add app/schemas docs/REVIEW.md` и остальные изменённые файлы. `app/schemas/` должен попасть в репозиторий (экспорт схемы Room включён).
3. Коммиты по блокам, сообщения по-английски в повелительном наклонении: `Fix nested folder paths in PROPFIND parsing`, `Delete upload record when a part file is cleaned up`, `Use API 26 compatible URL encoding` и т. д.
4. Push в `main`, затем смотреть `gh run list` и `gh run view --log-failed` (если `gh` не установлен, открыть страницу Actions и сообщить пользователю статус). Не говорить «зелёный» без проверки.

## R5. Тест на C2 и отмену запросов

`SyncRepository` завязан на `Context`, `Room`, `MediaStore`, поэтому на JVM его не протестировать. Не добавлять Robolectric. Вынести логику в тестируемые части:

```kotlin
interface UploadStore {
    suspend fun forPair(pairId: Long): List<UploadedEntity>
    suspend fun withParts(): List<UploadedEntity>
    suspend fun upsert(entity: UploadedEntity)
    suspend fun deleteOne(pairId: Long, mediaStoreId: Long)
}

interface RemoteFiles {
    suspend fun put(url: String, source: InputStream, length: Long): WebDavResult<Unit>
    suspend fun move(from: String, to: String): WebDavResult<Unit>
    suspend fun delete(url: String): WebDavResult<Unit>
}
```

Класс `UploadEngine(store, remote, urls)` с методами `cleanupParts(baseUrl)`, `uploadOne(...)`, `pending(files, known)`. Room-DAO и `WebDavClient` реализуют интерфейсы тонкими обёртками. В тестах — фейки в памяти.

Сценарии (каждый — отдельный тест):
1. `put` возвращает `Network` три раза → строки в хранилище нет, `delete(part)` вызван, файл снова в `pending`.
2. Процесс «убит» после записи строки с `partPath`: `cleanupParts` с `delete → Ok` удаляет строку, файл снова в `pending`.
3. То же, но `delete → Network` (нет сети): строка **остаётся** с `partPath`, `needsUpload` истина (R7).
4. Успех: `put` и `move` Ok → строка без `partPath`, файл не в `pending`.
5. Имя на сервере совпало по размеру → `AlreadyPresent`, строка записана без `put`.
6. После `Network` на первых N файлах пара прекращается (R8), остальные не трогаются.

Отмена запроса (`WebDavClientTest`): `MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS)`, запустить `client.propfind` в `launch`, через 200 мс `job.cancel()`, `job.join()` должен завершиться меньше чем за 2 секунды (иначе `Call.cancel()` не вызывается).

## R6. Фоновая выгрузка и `setForeground`

Принцип: пробовать `setForeground` всегда, но не раньше, чем появился первый файл на выгрузку, и без падения, если система запретила.

```kotlin
abstract class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected suspend fun tryForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (_: IllegalStateException) {
        false
    }
}
```

В `SyncWorker`/`MediaWatchWorker` убрать зависимость от `bypass`:

```kotlin
var foregroundAllowed = true
val failure = app.container.sync.runSync(pairId, bypass, token) { done, total ->
    if (foregroundAllowed) {
        val info = foreground(applicationContext.getString(R.string.sync_progress, done, total))
        foregroundAllowed = tryForeground(info)
    }
}
```

`onProgress` вызывается только когда есть что выгружать, поэтому уведомление не мигает каждые 15 минут. Если система запретила foreground, работа продолжается в фоне и ограничена ~10 минутами.

Документировать в README и `SERVER_NOTES.md`: надёжная выгрузка больших видео в фоне работает, только если приложение исключено из оптимизации батареи (экран синхронизации это предлагает). Иначе большой файл выгружается кнопкой «Синхронизировать сейчас». Проверить на телефоне с Android 12+: видео 1 ГБ в обоих режимах.

## R7. `.part` на сервере

В `uploadMedia` (и аналогично в движке из R5) при сбое удалять строку только если `.part` удалён:

```kotlin
if (!uploaded) {
    if (client.delete(partUrl) is WebDavResult.Ok) {
        database.uploadedDao().deleteOne(pair.id, file.id)
    }
    // иначе строка с partPath остаётся: needsUpload() для неё истина,
    // cleanupParts() удалит и part, и строку при следующем запуске
    val error = last ?: WebDavError.Network
    return UploadProblem(errorText(context, error), error.retries())
}
```

Для `uploadDocument` строки в `uploaded` нет. При неудачном `delete(partUrl)` записать путь в таблицу `OrphanPart(remotePath)` (миграция не нужна, пока приложение не выпущено; иначе увеличить версию БД и написать миграцию) и чистить её в начале `runUploads`.

## R8. Прерывание пары при серии сетевых ошибок

В `syncPair`:

```kotlin
var consecutiveNetworkFailures = 0
for ((index, file) in pending.withIndex()) {
    ...
    val problem = uploadMedia(baseUrl, pair, file, remoteName)
    if (problem == null) {
        consecutiveNetworkFailures = 0
        sizes[remoteName] = file.size
        continue
    }
    shown = problem.text
    if (problem.retryable) {
        retryMessage = problem.text
        if (++consecutiveNetworkFailures >= 3) return problem.text
    } else {
        consecutiveNetworkFailures = 0
    }
}
```

Возврат строки с временной ошибкой даёт `Result.retry()` с backoff.

## R9. Кнопка «Отмена»

1. В `syncNotification` использовать уникальный `requestCode`: `PendingIntent.getBroadcast(context, workId.hashCode(), intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)`.
2. Убрать глобальный `TransferControl`: в `CancelWorkReceiver` оставить только `WorkManager.cancelWorkById(...)`. Проверки отмены между файлами заменить на `currentCoroutineContext().ensureActive()`. Отмена корутины теперь прерывает и запрос (`Request.await`).
3. **Внимание после R6.** `cancelWorkById` для периодической работы отменяет всю серию. Либо показывать кнопку «Отмена» только у одноразовых работ (`syncNow`, `uploads`), либо после отмены вызывать `SyncScheduler.refresh(context, pairs)`. Выбрать первый вариант: проще и без побочных эффектов.

## R10. `setRequireOriginal` только для MediaStore

```kotlin
private fun openOriginal(uri: Uri, isMediaStore: Boolean): InputStream? {
    val canRequestOriginal = isMediaStore &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (canRequestOriginal) {
        try {
            return context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
        } catch (_: Exception) {
            // нет доступа к оригиналу: берём обычный поток
        }
    }
    return context.contentResolver.openInputStream(uri)
}
```

В `uploadMedia` передавать `true`, в `uploadDocument` — `false`.

## R11. Типизированные ошибки вместо сравнения строк

```kotlin
sealed interface SyncFailure {
    val retryable: Boolean
    data class Web(val error: WebDavError) : SyncFailure { override val retryable get() = error.retries() }
    data object NoConnection : SyncFailure { override val retryable = false }
    data object MediaPermission : SyncFailure { override val retryable = false }
    data object BadFolder : SyncFailure { override val retryable = false }
    data object LocalFile : SyncFailure { override val retryable = false }
}
```

- `runSync`/`runUploads` возвращают `SyncFailure?`, `UploadProblem` заменить на него. Воркеры: `if (failure?.retryable == true) Result.retry() else Result.success()`. Функцию `isTransientFailure` удалить.
- В `SyncPairEntity.lastError` хранить стабильный код (`"NETWORK"`, `"HTTP_503"`, `"MEDIA_PERMISSION"` …), текст собирать в UI через `stringResource`. Тип колонки остаётся `String?`, схема БД не меняется.
- Тест: `retries()` для `Network/Timeout/Ssl/Http(503)` → истина, для `Unauthorized/Forbidden/InsufficientStorage/Http(404)` → ложь.

## Мелочи

- **`refresh()`:** не обнулять `entries` при обновлении той же папки. Добавить параметр `refresh(clear: Boolean = false)`: `clear = true` только в коллекторе смены пути. Диалоги переименования и удаления находят запись по `relativePath` и не должны пропадать при обновлении.
- **`download`:** завести отдельный `StateFlow<Boolean>` `transferring` и показывать его отдельным индикатором. `loading` оставить только для листинга.
- **Пароль:** поле пароля на `remember`, а не `rememberSaveable`. URL, логин и флажок могут остаться на `rememberSaveable`.
- **`decideRemoteName`:** один раз на пару собирать `val lower = existing.mapKeys { it.key.lowercase() }` и искать `lower[candidate.lowercase()]`. Сигнатуру функции и `RemoteNamesTest` обновить.
- **Предупреждения lint (по желанию):** `String.toUri()` вместо `Uri.parse`, удалить неиспользуемые строки `files_title`, `permissions`, `loading`, убрать лишнюю проверку `SDK_INT >= 26` в `Workers.kt`.

## Что проверить руками после правок

1. Установить debug-APK на телефон с Android 12 или ниже, открыть папку с кириллицей и пробелами: нет крэша (R1).
2. Первое подключение по `http://` с включённым флажком работает, с выключенным показывает «Адрес http запрещён» (R3).
3. Выгрузка, оборванная выключением Wi-Fi, затем включить сеть: файл догружается, дублей и `.part` на сервере нет (R5, R7).
4. Автосинхронизация при закрытом приложении на Android 12+ с исключением из оптимизации батареи и без него (R6).
5. «Отмена» в уведомлении во время выгрузки большого видео прерывает его сразу (R9).
6. Вход в папку третьего уровня, удаление и перемещение там (C1).
