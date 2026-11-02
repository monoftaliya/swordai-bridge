# Архитектура SwordAI Bridge

Документ описывает, как устроены обе части системы, как между ними ходят данные и где что
лежит в коде.

## Обзор

Система состоит из трёх участников:

1. **ChatGPT** — клиент MCP. Не входит в репозиторий; подключается по HTTPS к `/mcp`.
2. **Cloud Bridge** — серверное приложение (Python/ASGI). Единая точка, через которую
   ChatGPT общается с телефоном. Хранит устройства, токены, журнал, подписывает команды.
3. **Android Agent** — приложение на телефоне. Держит WebSocket-соединение с сервером,
   проверяет и выполняет команды, спрашивает подтверждения, возвращает результат.

```
         OAuth 2.1 + MCP (HTTPS)                 Протокол команд v1 (WSS)
┌─────────┐                      ┌──────────────────────────┐                    ┌──────────────────┐
│ ChatGPT │ ───── /mcp ────────▶ │        Cloud Bridge      │ ──── /ws/device ──▶│  Android Agent   │
│  (MCP)  │ ◀──── результат ──── │  Starlette + MCP SDK     │ ◀─── результат ────│  (телефон)       │
└─────────┘                      │                          │                    └──────────────────┘
                                 │  ┌────────────────────┐  │                      │
   человек ── вход/согласие ───▶ │  │ OAuth AS (DCR,PKCE) │  │     Accessibility ───┤ нажатия, чтение
   (страница /oauth/consent)     │  ├────────────────────┤  │     MediaProjection ─┤ скриншот
                                 │  │ CommandService      │  │     NotificationLstnr┤ уведомления
   админ ── /admin ───────────▶  │  │ (подпись, повторы)  │  │     SAF ─────────────┤ файлы
                                 │  ├────────────────────┤  │                      │
                                 │  │ Hub (WebSocket)     │  │     подтверждения ───┤ уведомления/экран
                                 │  ├────────────────────┤  │                      │
                                 │  │ Store → SQLite/PG   │  │     аварийная ───────┘ СТОП
                                 │  └────────────────────┘  │
                                 └──────────────────────────┘
```

---

## Cloud Bridge (server/)

ASGI-приложение на **Starlette**. MCP-часть смонтирована официальным **MCP SDK** (streamable
HTTP, stateless). Один рабочий поток БД, всё остальное — асинхронно.

### Слои

- **Транспорт/маршруты** (`bridge/app.py`) — сборка приложения: статические страницы,
  `/healthz`, админ-API, device-API, WebSocket-маршрут `/ws/device`, монтирование MCP на `/mcp`,
  временные ссылки на скриншоты `/s/{token}`, well-known для OAuth. MCP отключается, если адрес
  не HTTPS и не localhost (MCP требует защищённый контекст).
- **MCP + OAuth** (`bridge/mcp_bridge/`)
  - `tools.py` — объявление 15 инструментов для ChatGPT, проверка области доступа на каждый
    вызов, строгая проверка аргументов, рендеринг результата (текст + изображение + временная
    ссылка на скриншот).
  - `oauth.py` — провайдер OAuth 2.1: динамическая регистрация клиентов (DCR), PKCE S256,
    выпуск/обновление токенов, обнаружение повторного использования refresh-токена, список
    разрешённых хостов redirect.
  - `consent.py` — страница входа и согласия (пароль админа, выбор области доступа, CSRF).
- **Логика команд** (`bridge/commands/`)
  - `schemas.py` — строгие Pydantic-модели аргументов каждого инструмента (`extra="forbid"`),
    реестр инструментов `TOOLS`, области `device.read` / `device.control`.
  - `service.py` — `CommandService`: выбор устройства, валидация, присвоение номера
    последовательности, формирование и **подпись** полезной нагрузки, запись в БД и аудит,
    ожидание результата, переотправка при переподключении, обработка сообщений от устройства.
- **Устройства** (`bridge/devices/`)
  - `hub.py` — реестр живых WebSocket-соединений (`Hub`), по одному активному на устройство.
  - `ws.py` — обработчик `/ws/device`: аутентификация по токену, приветствие, heartbeat,
    приём `ack`/`progress`/`result`/`event`, коды закрытия.
- **Хранилище** (`bridge/db/`, `bridge/store.py`) — абстракция над SQLite и PostgreSQL
  (один диалект-независимый слой), репозитории: устройства, коды пэйринга, команды, аудит,
  OAuth-клиенты/коды/токены, сессии админа.
- **Безопасность** (`bridge/security/`) — токены и их хеширование, ключ подписи (ECDSA P-256,
  вывод из seed через HKDF), ограничение частоты, аутентификация админа (scrypt).
- **Веб** (`bridge/web/`) — страницы согласия/сообщений, админ-SPA (`admin.html`, рендеринг
  только через `textContent`, без вставки HTML).

### Входные точки
- `python -m bridge` (`bridge/__main__.py`) — запускает uvicorn.
- Фабрика приложения — `bridge/app.py:create_app()`.

---

## Android Agent (android/)

Приложение на **Kotlin** + **Jetpack Compose** (Material 3). Разделено на «чистое ядро»
(без зависимостей от Android, покрыто JVM-тестами) и платформенный слой.

### Чистое ядро (`core/`, покрыто тестами)
Не зависит от Android API, поэтому проверяется обычными JUnit-тестами на JVM:
- `protocol/CommandVerifier.kt` — проверка ECDSA-подписи команды, версии протокола,
  адресата (device_id).
- `protocol/Messages.kt` — модели сообщений протокола.
- `security/ReplayGuard.kt` — защита от повторов (уникальный id, монотонный номер, срок).
- `security/SecurityPolicy.kt` — политика: защищённые приложения, поля паролей, значимые
  слова, режимы подтверждения, чувствительные системные пакеты.
- `security/Redactor.kt` — маскирование кодов/карт в тексте уведомлений.
- `commands/ToolArgs.kt` — строгий разбор и типизация аргументов инструментов.
- `net/Backoff.kt` — экспоненциальная задержка переподключения с джиттером.
- `files/NameMatcher.kt`, `screen/ScreenModel.kt`, `log/LogEntry.kt`, `Json.kt`, `Ids.kt`.

### Платформенный слой
- **Сервис-связь** (`service/BridgeService.kt`) — Foreground Service (`specialUse` +
  `mediaProjection` на время захвата). Держит соединение, уведомление, heartbeat.
- **Соединение** (`net/BridgeConnection.kt`) — OkHttp WebSocket: ping 25 c, heartbeat,
  переподключение с бэкоффом, реакция на смену сети.
- **Пэйринг** (`net/PairingClient.kt`) — обмен кода пэйринга на токен устройства по REST.
- **Выполнение** (`agent/CommandExecutor.kt`, `agent/ToolRunner.kt`) — проверка подписи и
  повтора → проверка политики → (подтверждение) → выполнение → результат.
- **Accessibility** (`accessibility/AgentAccessibilityService.kt`, `ScreenReader.kt`,
  `UiActions.kt`, `OverlayIndicator.kt`) — чтение дерева интерфейса, жесты (нажатие,
  прокрутка, ввод), экранный индикатор активности.
- **Захват экрана** (`capture/ScreenCapture.kt`, `CaptureConsentActivity.kt`) —
  MediaProjection с **обязательным** системным согласием на каждую сессию захвата.
- **Файлы** (`files/FileGateway.kt`) — поиск/открытие только в разрешённых пользователем
  папках (SAF, доступ только на чтение).
- **Уведомления** (`notifications/NotificationCollector.kt`) — чтение уведомлений (если
  пользователь выдал доступ), с маскированием секретов.
- **Подтверждения** (`agent/ConfirmationManager.kt`) — запрос подтверждения значимых
  действий на телефоне.
- **Аварийная остановка** (`agent/EmergencyStop.kt`, `service/StopTileService.kt`,
  `service/ActionReceiver.kt`).
- **Автозапуск** (`service/BootReceiver.kt`) — восстановление после перезагрузки/обновления.
- **UI** (`ui/`) — экраны на Compose: пэйринг, главный экран (статусы, устройство, журнал,
  кнопка аварийной остановки), журнал, разрешения, настройки, защищённые приложения.

---

## Поток одной команды (end-to-end)

1. **ChatGPT → сервер.** Вызов MCP-инструмента `get_screen_info` с OAuth-токеном.
2. **Проверка доступа.** Сервер проверяет токен и область (`device.read`/`device.control`),
   строго валидирует аргументы.
3. **Формирование команды.** `CommandService` выбирает устройство, присваивает монотонный
   номер, собирает полезную нагрузку (детерминированный JSON с сортировкой ключей),
   **подписывает** её закрытым ключом (ECDSA P-256) и пишет в БД + аудит.
4. **Доставка.** Если устройство офлайн — короткий период ожидания переподключения. Иначе
   команда уходит в WebSocket.
5. **Проверка на телефоне.** Agent проверяет подпись закреплённым открытым ключом, проверяет
   версию и адресата, проверяет отсутствие повтора (id/номер/срок).
6. **Политика и подтверждение.** `SecurityPolicy` решает: заблокировать, выполнить или
   запросить подтверждение. Значимые действия — всегда с подтверждением на телефоне.
7. **Выполнение.** Для действий — жесты через Accessibility; для скриншота — MediaProjection
   (после согласия); для файлов — SAF.
8. **Результат.** Agent отправляет `result`; сервер кэширует его и отдаёт ChatGPT. Если связь
   прервалась — результат переотправляется при переподключении (доставка «не более одного раза»
   по id команды).

---

## Хранилище данных

Таблицы (см. `bridge/db/schema.py`): `kv`, `devices`, `pairing_codes`, `commands`,
`audit_log`, `oauth_clients`, `oauth_pending`, `oauth_codes`, `oauth_tokens`, `admin_sessions`.
Один слой работает и с SQLite (v1, по умолчанию), и с PostgreSQL (путь роста). Токены хранятся
**в виде хешей** (SHA-256 для токенов устройств, scrypt для пароля админа).

---

## Дерево файлов (сокращённо)

```
server/
├── bridge/
│   ├── app.py __main__.py config.py store.py screenshots.py
│   ├── db/           database.py schema.py
│   ├── security/     tokens.py signing.py ratelimit.py admin_auth.py
│   ├── commands/     schemas.py service.py
│   ├── devices/      hub.py ws.py
│   ├── mcp_bridge/   tools.py oauth.py consent.py
│   ├── api/          device_api.py admin_api.py
│   └── web/          pages.py admin.html
├── testkit/          device_sim.py asgi_ws.py
├── tests/            test_devices.py test_mcp.py test_units.py conftest.py
├── tools/            bridgectl.py fake_device.py make_interop_fixture.py
├── Dockerfile  requirements.txt  requirements-dev.txt  .env.example
│
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/swordai/bridge/
│   │   ├── core/         (чистое ядро, покрыто тестами)
│   │   ├── agent/        CommandExecutor ToolRunner ConfirmationManager EmergencyStop …
│   │   ├── accessibility/ capture/ files/ notifications/ net/ service/ apps/
│   │   ├── data/         Settings SecretStore PairingStore ReplayStore ActionLog
│   │   └── ui/           theme/ components/ screens/
│   └── res/              values/ xml/ drawable/ mipmap-anydpi-v26/
├── app/src/test/        CommandVerifierTest SecurityCoreTest ToolArgsTest
└── gradle/              libs.versions.toml wrapper/
│
.github/workflows/       android.yml  server.yml
```

Подробности протокола — в [`docs/PROTOCOL.md`](docs/PROTOCOL.md); модель безопасности — в
[`docs/SECURITY.md`](docs/SECURITY.md).
