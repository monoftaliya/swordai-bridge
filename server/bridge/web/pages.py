"""Небольшие HTML-страницы, отрисовываемые сервером (без шаблонизатора)."""

from __future__ import annotations

from html import escape

BASE_CSS = """
:root{--bg:#07080c;--panel:rgba(22,24,34,.72);--line:rgba(140,150,255,.18);--text:#e8ebff;--muted:#8d93b5;
--blue:#3d7bff;--violet:#8a5cff;--ok:#2fd39a;--warn:#ffb547;--bad:#ff5470}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--text);
font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
body{min-height:100vh;background:radial-gradient(900px 500px at 10% -10%,rgba(61,123,255,.22),transparent 60%),
radial-gradient(800px 500px at 110% 10%,rgba(138,92,255,.20),transparent 60%),var(--bg)}
.wrap{max-width:560px;margin:0 auto;padding:32px 16px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:22px;padding:24px;
backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);box-shadow:0 20px 60px rgba(0,0,0,.45)}
h1{font-size:22px;margin:0 0 6px}p{margin:8px 0;color:var(--muted)}b,strong{color:var(--text)}
.brand{display:flex;align-items:center;gap:10px;margin-bottom:18px;font-weight:700;letter-spacing:.3px}
.logo{width:34px;height:34px;border-radius:10px;background:linear-gradient(135deg,var(--blue),var(--violet));
box-shadow:0 0 24px rgba(110,100,255,.6)}
label{display:block;margin:14px 0 6px;color:var(--muted);font-size:14px}
input[type=password],input[type=text]{width:100%;padding:12px 14px;border-radius:14px;border:1px solid var(--line);
background:rgba(10,12,20,.8);color:var(--text);font-size:16px;outline:none}
input:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(61,123,255,.25)}
.scope{display:flex;gap:12px;align-items:flex-start;padding:12px;border:1px solid var(--line);border-radius:14px;margin:10px 0}
.scope input{margin-top:5px;width:18px;height:18px;accent-color:var(--violet)}
.row{display:flex;gap:10px;margin-top:20px}
button{flex:1;padding:13px 16px;border-radius:14px;border:0;font-size:16px;font-weight:600;cursor:pointer;color:#fff}
.primary{background:linear-gradient(135deg,var(--blue),var(--violet))}
.ghost{background:transparent;border:1px solid var(--line);color:var(--text)}
.error{color:var(--bad);margin-top:12px}.note{font-size:13px}
code{background:rgba(255,255,255,.06);padding:2px 6px;border-radius:6px}
"""

SECURITY_HEADERS = {
    "X-Frame-Options": "DENY",
    "Content-Security-Policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
    "img-src 'self' data:; frame-ancestors 'none'; "
    "form-action 'self' https: http://localhost:* http://127.0.0.1:*",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
}


def page(title: str, body: str) -> str:
    return (
        "<!doctype html><html lang='ru'><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        f"<title>{escape(title)}</title><style>{BASE_CSS}</style></head>"
        f"<body><div class='wrap'><div class='card'><div class='brand'><div class='logo'></div>SwordAI Bridge</div>"
        f"{body}</div></div></body></html>"
    )


def consent_page(
    *,
    pending_id: str,
    csrf: str,
    client_name: str,
    redirect_host: str,
    wants_control: bool,
    error: str | None = None,
) -> str:
    control_checked = "checked" if wants_control else ""
    error_html = f"<p class='error'>{escape(error)}</p>" if error else ""
    body = f"""
<h1>Разрешить доступ к телефону?</h1>
<p><b>{escape(client_name)}</b> запрашивает подключение к SwordAI Bridge.
Ответ будет отправлен на <code>{escape(redirect_host)}</code>.</p>
<form method="post" action="/oauth/consent" autocomplete="off">
  <input type="hidden" name="req" value="{escape(pending_id)}">
  <input type="hidden" name="csrf" value="{escape(csrf)}">
  <div class="scope"><input type="checkbox" checked disabled>
    <div><b>Чтение</b><p class="note">Состояние телефона, содержимое экрана, скриншоты (с вашего согласия на
    телефоне), уведомления и поиск файлов в разрешённых папках.</p></div></div>
  <div class="scope"><input type="checkbox" name="control" value="1" {control_checked}>
    <div><b>Управление</b><p class="note">Открывать приложения, нажимать, прокручивать и вводить текст.
    Значимые действия всё равно подтверждаются на телефоне.</p></div></div>
  <label for="password">Пароль администратора сервера</label>
  <input id="password" type="password" name="password" required autofocus>
  {error_html}
  <div class="row">
    <button class="ghost" type="submit" name="action" value="deny" formnovalidate>Отклонить</button>
    <button class="primary" type="submit" name="action" value="allow">Разрешить</button>
  </div>
</form>
<p class="note">Доступ можно отозвать в любой момент в админ-панели (<code>/admin</code>).</p>
"""
    return page("Подключение к SwordAI Bridge", body)


def message_page(title: str, text: str) -> str:
    return page(title, f"<h1>{escape(title)}</h1><p>{escape(text)}</p>")


def landing_page(version: str, mcp_url: str) -> str:
    body = f"""
<h1>Cloud Bridge работает</h1>
<p>Версия {escape(version)}. Адрес MCP-сервера для ChatGPT:</p>
<p><code>{escape(mcp_url)}</code></p>
<div class="row"><a href="/admin" style="flex:1;text-decoration:none">
<button class="primary" type="button">Открыть админ-панель</button></a></div>
"""
    return page("SwordAI Bridge", body)
