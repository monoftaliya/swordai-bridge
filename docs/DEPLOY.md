# Развёртывание Cloud Bridge

Сервер можно поднять бесплатно (Render + Neon), на своём VPS (Docker) или локально в домашней
сети (для тестов этапа 2). Везде нужны три вещи: публичный адрес, пароль администратора и
ключ подписи.

---

## Переменные окружения

| Переменная | Обязательна | По умолчанию | Назначение |
|-----------|:-----------:|--------------|-----------|
| `PUBLIC_URL` | да (кроме Render) | `http://localhost:8000` | Публичный адрес без слеша. На Render берётся из `RENDER_EXTERNAL_URL`. |
| `ADMIN_PASSWORD` | да | — | Вход в `/admin` и согласие OAuth. Минимум 10 символов. |
| `SIGNING_KEY` | настоятельно | генерируется в БД | Ключ подписи команд (PEM / base64-PEM / seed ≥ 32 символов). **Задайте явно**, если БД может пропасть, иначе телефоны придётся сопрягать заново. |
| `DATABASE_URL` | нет | `sqlite:///data/bridge.db` | `postgresql://…` для PostgreSQL. |
| `OAUTH_REDIRECT_HOSTS` | нет | `chatgpt.com,chat.openai.com` | Разрешённые хосты redirect. |
| `ALLOW_LOOPBACK_REDIRECTS` | нет | `true` | Разрешить redirect на localhost (тесты). |

Остальные тонкие настройки — в `server/.env.example`.

> **Почему важен `SIGNING_KEY`.** Устройство закрепляет открытый ключ сервера при пэйринге.
> Если сервер перезапустится с другим ключом (например, потеряв БД на бесплатном тарифе),
> телефоны перестанут принимать команды, пока их не сопрягут заново. Фиксированный
> `SIGNING_KEY` (seed-строка) решает это — ключ выводится из seed детерминированно (HKDF).

---

## Вариант 1. Render (бесплатно) + Neon PostgreSQL (бесплатно) — рекомендуется

Готовый `render.yaml` уже в корне репозитория.

1. **База (Neon).** Создайте бесплатный проект на Neon, скопируйте строку подключения вида
   `postgresql://user:password@host/dbname?sslmode=require`.
2. **Сервис (Render).** *New → Blueprint* → выберите этот репозиторий. Render прочитает
   `render.yaml`.
3. Задайте переменные при создании:
   - `ADMIN_PASSWORD` — ваш длинный пароль (в blueprint помечен `sync:false`).
   - `DATABASE_URL` — строка от Neon (`sync:false`).
   - `SIGNING_KEY` — Render сгенерирует (`generateValue:true`). Можно заменить своей seed-строкой.
4. Дождитесь деплоя. Проверка здоровья — `/healthz`. Админка — `https://<ваш>.onrender.com/admin`.

**Нюансы бесплатного тарифа Render:**
- Сервис засыпает после ~15 минут без входящего трафика. Сообщения WebSocket от подключённого
  телефона считаются трафиком и держат его активным, но при полном простое первый запрос
  «разбудит» сервис с задержкой.
- Диск временный — поэтому БД выносим в Neon, а `SIGNING_KEY` фиксируем.
- Neon на бесплатном тарифе тоже «засыпает»; сервер написан так, чтобы не дёргать БД в простое.

---

## Вариант 2. Свой VPS через Docker (SQLite или PostgreSQL)

В `server/Dockerfile` — образ на `python:3.13-slim`, запуск от непривилегированного пользователя,
том `/app/data`, healthcheck.

```bash
cd server
docker build -t swordai-bridge .

docker run -d --name swordai-bridge \
  -p 8000:8000 \
  -e PUBLIC_URL=https://bridge.example.com \
  -e ADMIN_PASSWORD='ваш-длинный-пароль' \
  -e SIGNING_KEY='случайная-строка-не-короче-32-символов' \
  -v swordai-data:/app/data \
  swordai-bridge
```

Для PostgreSQL добавьте `-e DATABASE_URL=postgresql://user:pass@host/db`. Том `/app/data`
нужен только для SQLite.

> **HTTPS обязателен для MCP.** Поставьте перед контейнером обратный прокси с TLS (Caddy,
> nginx, Traefik). На не-HTTPS адресе (кроме localhost) MCP-часть отключается намеренно.

---

## Вариант 3. Локально в домашней сети (для теста этапа 2)

Для проверки «телефон ↔ сервер» без облака, когда телефон и компьютер в одной сети.

```bash
cd server
pip install -r requirements.txt
PUBLIC_URL=http://192.168.1.50:8000 ADMIN_PASSWORD=test1234567 \
  SIGNING_KEY=local-dev-seed-012345678901234567890123 \
  python -m bridge
```

- Укажите реальный IP компьютера в локальной сети как `PUBLIC_URL`.
- Для локального теста ставьте **отладочный** APK: в нём разрешён нешифрованный трафик к LAN
  (`network_security_config` в `src/debug`). Релизный APK требует HTTPS.
- Проверить без телефона можно:
  - CLI `server/tools/bridgectl.py` (стандартная библиотека Python) — пэйринг, отправка команд,
    просмотр журнала;
  - симулятор `server/tools/fake_device.py` (нужен `pip install websockets`) — изображает
    устройство на WebSocket. *(Этот симулятор не прогонялся против живого WS — см.
    [TESTING.md](TESTING.md).)*

Пример с CLI:
```bash
cd server
python tools/bridgectl.py --url http://localhost:8000 login
python tools/bridgectl.py pair-code          # создать код пэйринга
python tools/bridgectl.py devices            # список устройств
python tools/bridgectl.py send <device_id> get_device_status
```

---

## После развёртывания
1. Зайдите в `/admin`, создайте код пэйринга, сопрягите телефон.
2. Проверьте, что устройство онлайн и статусы зелёные.
3. Подключите ChatGPT — см. [CHATGPT.md](CHATGPT.md).

Диагностика: `/healthz` (здоровье), раздел «Журнал/аудит» в админке, логи контейнера/сервиса.
