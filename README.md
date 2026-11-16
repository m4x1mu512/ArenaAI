# Coarena Chat (Android)

[Русский](#русский) | [English](#english)

---

## Русский

**Coarena Chat** — мобильное Android-приложение на Kotlin, Jetpack Compose и Material 3 для общения с моделями через API [Coarena](https://coarena.ai).

### Возможности
- **Первый запуск и настройка**: ввод Coarena API key (с переключением видимости), настраиваемый Base URL (`https://coarena.ai/api/v1` по умолчанию), проверка подключения перед сохранением.
- **Чат со стримингом SSE**: потоковый вывод ответов (`text/event-stream`) с автоматическим переходом на обычный JSON-запрос без `stream: true`, если стриминг недоступен.
- **Выбор моделей**: загрузка списка моделей из `GET /api/v1/models` с поиском в bottom sheet и сохранением модели по умолчанию.
- **Экран настроек и аккаунта**: просмотр баланса (`GET /api/v1/credits`), лимитов (`GET /api/v1/limits`), истории запросов (`GET /api/v1/requests`), переключение темы (Системная / Светлая / Тёмная) и полное удаление локальных данных.
- **Безопасность**: API-ключ шифруется через **Android Keystore (AES-256-GCM)**, хранится только локально и автоматически маскируется во всех диагностических сообщениях об ошибках. Аналитика и трекеры отсутствуют.

### Как получить Coarena API key
1. Откройте личный кабинет Coarena: **https://coarena.ai/account/api**
2. Создайте новый API-ключ и скопируйте его.
3. Запустите приложение Coarena Chat, вставьте ключ в поле **Coarena API key** и нажмите **Проверить подключение** → **Сохранить и продолжить**.

### Как открыть проект в Android Studio
1. Установите **Android Studio** (рекомендуется актуальная стабильная версия с JDK 17).
2. Выберите **File → Open** и укажите корневую папку репозитория.
3. Дождитесь завершения Gradle Sync.

### Как запустить debug-сборку
- Через Android Studio: выберите конфигурацию `app` (build variant `debug`) и нажмите **Run**.
- Через терминал:
  ```bash
  ./gradlew assembleDebug
  ```
  APK будет создан в `app/build/outputs/apk/debug/`.

### Как собрать release
1. Сгенерируйте или используйте существующий `.jks`/`.keystore` файл (с ключом `upload`).
2. Задайте переменные окружения и выполните сборку:
   ```bash
   export KEYSTORE_PATH=/path/to/release.jks
   export STORE_PASSWORD=your_store_password
   export KEY_PASSWORD=your_key_password
   ./gradlew assembleRelease bundleRelease
   ```
   Артефакты будут сохранены в `app/build/outputs/apk/release/` и `app/build/outputs/bundle/release/`.

### Настройка GitHub Secrets для CI/CD
Для работы release-джоба в `.github/workflows/android.yml` добавьте в **Settings → Secrets and variables → Actions** следующие секреты:
- `ANDROID_KEYSTORE_BASE64` — содержимое файла keystore, закодированное в Base64 (`base64 -w 0 release.jks`).
- `ANDROID_KEYSTORE_PASSWORD` — пароль от keystore.
- `ANDROID_KEY_ALIAS` — alias ключа (в конфигурации по умолчанию используется `upload`).
- `ANDROID_KEY_PASSWORD` — пароль от ключа.

### Как изменить Base URL и модель
- **На экране первого запуска**: отредактируйте поле **Base URL** перед сохранением.
- **В работающем приложении**: откройте **Настройки** (иконка шестерёнки в правом верхнем углу чата), измените **Base URL** или выберите **Модель по умолчанию** и нажмите **Сохранить настройки**. Для смены модели в текущем чате нажмите на чип с названием модели в верхней панели.

---

## English

**Coarena Chat** is a native Android client built with Kotlin, Jetpack Compose, and Material 3 for chatting with AI models via the [Coarena](https://coarena.ai) API.

### Features
- **First-Launch Setup**: Masked API key input with visibility toggle, customizable Base URL (defaults to `https://coarena.ai/api/v1`), and pre-save connection test.
- **Streaming SSE Chat**: Live token streaming (`text/event-stream`) with automatic fallback to non-streaming JSON (`stream: false`) if streaming is unsupported.
- **Model Selector**: Fetches available models from `GET /api/v1/models` with a searchable Material 3 bottom sheet.
- **Settings & Account Overview**: Displays credit balance (`GET /api/v1/credits`), rate limits (`GET /api/v1/limits`), recent request history (`GET /api/v1/requests`), theme switching (System / Light / Dark), and confirmed local key/data deletion.
- **Privacy & Security**: The API key is encrypted with **Android Keystore (AES-256-GCM)**, stored strictly on-device, and redacted from error diagnostics. No analytics, ads, or trackers are included.

### How to Get a Coarena API Key
1. Visit your Coarena API dashboard: **https://coarena.ai/account/api**
2. Generate and copy a personal API key.
3. Launch Coarena Chat, paste the key into **Coarena API Key**, and tap **Test Connection** → **Save & Continue**.

### How to Open the Project in Android Studio
1. Install a current stable version of **Android Studio** configured with **JDK 17**.
2. Choose **File → Open** and select the project root directory.
3. Wait for Gradle Sync to finish.

### How to Run a Debug Build
- From Android Studio: select the `debug` build variant and click **Run**.
- From the terminal:
  ```bash
  ./gradlew assembleDebug
  ```
  The APK is generated under `app/build/outputs/apk/debug/`.

### How to Build a Release
1. Prepare a signing keystore (with alias `upload`).
2. Export the signing environment variables and run:
   ```bash
   export KEYSTORE_PATH=/path/to/release.jks
   export STORE_PASSWORD=your_store_password
   export KEY_PASSWORD=your_key_password
   ./gradlew assembleRelease bundleRelease
   ```
   Outputs are placed in `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/`.

### Required GitHub Secrets
To enable the signed release workflow in `.github/workflows/android.yml`, configure these repository secrets under **Settings → Secrets and variables → Actions**:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded keystore file (`base64 -w 0 release.jks`).
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password.
- `ANDROID_KEY_ALIAS`: Key alias (`upload` in the default signing configuration).
- `ANDROID_KEY_PASSWORD`: Private key password.

### How to Change Base URL and Model
- **During initial setup**: edit the **Base URL** field before tapping **Save & Continue**.
- **In the app**: open **Settings** from the chat top bar to update **Base URL**, pick a **Default Model**, or tap the model pill in the chat top bar to switch models at any time.
