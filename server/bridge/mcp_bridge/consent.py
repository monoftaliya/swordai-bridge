"""Страница согласия OAuth: владелец вводит пароль администратора и выбирает права."""

from __future__ import annotations

from urllib.parse import urlparse

from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response

from ..commands.schemas import SCOPE_CONTROL
from ..security.tokens import consteq
from ..web.pages import SECURITY_HEADERS, consent_page, message_page


def _html(content: str, status: int = 200) -> HTMLResponse:
    return HTMLResponse(content, status_code=status, headers=SECURITY_HEADERS)


async def consent_get(request: Request) -> Response:
    state = request.app.state.bridge
    if state.oauth is None:
        return _html(message_page("MCP отключён", "Сервер запущен без HTTPS, OAuth недоступен."), 404)
    pending_id = request.query_params.get("req", "")
    loaded = await state.oauth.load_pending(pending_id)
    if loaded is None:
        return _html(message_page("Запрос устарел", "Начните подключение в ChatGPT заново."), 400)
    row, params, client = loaded
    wants_control = params.scopes is None or SCOPE_CONTROL in params.scopes
    return _html(
        consent_page(
            pending_id=pending_id,
            csrf=row["csrf"],
            client_name=client.client_name or "Неизвестное приложение",
            redirect_host=urlparse(str(params.redirect_uri)).netloc,
            wants_control=wants_control,
        )
    )


async def consent_post(request: Request) -> Response:
    state = request.app.state.bridge
    if state.oauth is None:
        return _html(message_page("MCP отключён", "OAuth недоступен."), 404)
    ip = state.client_ip(request)
    form = await request.form()
    pending_id = str(form.get("req", ""))
    csrf = str(form.get("csrf", ""))
    action = str(form.get("action", ""))
    loaded = await state.oauth.load_pending(pending_id)
    if loaded is None:
        return _html(message_page("Запрос устарел", "Начните подключение в ChatGPT заново."), 400)
    row, params, client = loaded
    if not consteq(csrf, row["csrf"]):
        return _html(message_page("Ошибка безопасности", "Неверный токен формы. Обновите страницу."), 400)

    if action == "deny":
        url = await state.oauth.complete(pending_id, approve=False, grant_control=False)
        return RedirectResponse(url, status_code=303) if url else _html(message_page("Готово", "Запрос уже обработан."))

    def form_again(error: str, status: int) -> Response:
        return _html(
            consent_page(
                pending_id=pending_id,
                csrf=row["csrf"],
                client_name=client.client_name or "Неизвестное приложение",
                redirect_host=urlparse(str(params.redirect_uri)).netloc,
                wants_control=form.get("control") == "1",
                error=error,
            ),
            status,
        )

    if not state.limiter.allow(f"consent:{ip}", limit=8, window=600) or not state.limiter.allow(
        "consent:global", limit=40, window=600
    ):
        await state.store.audit("owner", "security.consent_rate_limited", ip=ip)
        return form_again("Слишком много попыток. Подождите 10 минут.", 429)
    password = str(form.get("password", ""))
    if not state.admin.verify(password):
        await state.store.audit("owner", "security.consent_bad_password", ip=ip, details={"client_id": client.client_id})
        return form_again("Неверный пароль.", 401)
    url = await state.oauth.complete(pending_id, approve=True, grant_control=form.get("control") == "1")
    if url is None:
        return _html(message_page("Запрос устарел", "Начните подключение в ChatGPT заново."), 400)
    return RedirectResponse(url, status_code=303)
