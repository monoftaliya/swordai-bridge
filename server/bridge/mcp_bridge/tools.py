"""MCP-сервер SwordAI Bridge: инструменты, которые вызывает ChatGPT."""

from __future__ import annotations

import inspect
import json
import logging
import time
from collections.abc import Mapping
from typing import Annotated, Any, Literal

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import Context, MCPServer
from mcp_types import CallToolResult, ImageContent, TextContent, ToolAnnotations
from pydantic import Field

from .. import __version__
from ..commands.schemas import (
    ALL_SCOPES,
    COMMAND_ID,
    DEVICE_ID,
    ELEMENT_ID,
    FILE_ID,
    PACKAGE_NAME,
    SCOPE_READ,
    SNAPSHOT_ID,
    required_scope,
)
from ..commands.service import CommandError, CommandService
from ..config import Settings
from ..screenshots import ScreenshotStore
from ..store import Store
from .oauth import BridgeOAuthProvider

log = logging.getLogger("bridge.mcp")

INSTRUCTIONS = """SwordAI Bridge управляет Android-смартфоном пользователя.
Правила работы:
1. Начинайте с get_device_status, затем get_screen_info — это основной способ «видеть» экран (id элементов e1, e2…).
2. Для нажатий используйте tap_element с element_id из последнего get_screen_info (передавайте и snapshot_id).
3. После каждого действия снова вызывайте get_screen_info, чтобы проверить результат.
4. Значимые действия (отправка, удаление, оплата, системные настройки) пользователь подтверждает на телефоне.
   Если пришёл статус pending — подождите и вызовите get_command_result с command_id.
5. Банковские приложения, менеджеры паролей и поля паролей заблокированы — не пытайтесь их обойти.
6. Если экран заблокирован, попросите пользователя разблокировать телефон: обход блокировки невозможен.
capture_screen возвращает изображение и ссылку; если изображение вам недоступно, опирайтесь на get_screen_info."""

DeviceIdArg = Annotated[
    str | None,
    Field(description="ID устройства (dev_…). Можно не указывать, если подключено одно устройство.", pattern=DEVICE_ID),
]

READ_ONLY = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)
ACTION = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=True)
RISKY_ACTION = ToolAnnotations(read_only_hint=False, destructive_hint=True, idempotent_hint=False, open_world_hint=True)


class BridgeMCP:
    """Сборка MCPServer и обработка вызовов инструментов."""

    def __init__(
        self,
        settings: Settings,
        store: Store,
        service: CommandService,
        provider: BridgeOAuthProvider,
        screenshots: ScreenshotStore,
    ) -> None:
        self.settings = settings
        self.store = store
        self.service = service
        self.provider = provider
        self.screenshots = screenshots
        self._client_names: dict[str, tuple[str, float]] = {}
        self.tool_params: dict[str, set[str]] = {}
        self.server = MCPServer(
            name="swordai-bridge",
            title="SwordAI Bridge",
            description="Управление Android-смартфоном через защищённый облачный канал",
            instructions=INSTRUCTIONS,
            version=__version__,
            auth_server_provider=provider,
            auth=AuthSettings(
                issuer_url=settings.public_url,  # type: ignore[arg-type]
                resource_server_url=settings.mcp_url,  # type: ignore[arg-type]
                validate_token_resource=False,  # аудиторию проверяет наш провайдер
                client_registration_options=ClientRegistrationOptions(
                    enabled=True, valid_scopes=ALL_SCOPES, default_scopes=ALL_SCOPES
                ),
                revocation_options=RevocationOptions(enabled=True),
                required_scopes=None,
            ),
            middleware=[self._strict_arguments],
        )
        self._register_tools()

    # --- инфраструктура ------------------------------------------------------------
    def _add(self, fn: Any, *, title: str, description: str, annotations: ToolAnnotations) -> None:
        self.server.add_tool(fn, name=fn.__name__, title=title, description=description, annotations=annotations)
        params = {
            name
            for name, p in inspect.signature(fn).parameters.items()
            if name != "ctx" and p.kind in (p.POSITIONAL_OR_KEYWORD, p.KEYWORD_ONLY)
        }
        self.tool_params[fn.__name__] = params

    async def _strict_arguments(self, ctx: Any, call_next: Any) -> Any:
        """Отклоняем неизвестные аргументы до вызова инструмента (строгая проверка)."""
        if getattr(ctx, "method", None) == "tools/call" and isinstance(ctx.params, Mapping):
            name = ctx.params.get("name")
            arguments = ctx.params.get("arguments") or {}
            allowed = self.tool_params.get(name) if isinstance(name, str) else None
            if allowed is not None and isinstance(arguments, Mapping):
                unknown = sorted(set(arguments) - allowed)
                if unknown:
                    return CallToolResult(
                        content=[
                            TextContent(
                                type="text",
                                text=f"Ошибка [bad_args]: неизвестные аргументы {', '.join(unknown)}. "
                                f"Допустимые: {', '.join(sorted(allowed)) or 'нет'}.",
                            )
                        ],
                        is_error=True,
                    )
        return await call_next(ctx)

    def _token(self, ctx: Context) -> AccessToken | None:
        token = get_access_token()
        if token is not None:
            return token
        request = getattr(ctx.request_context, "request", None)
        user = request.scope.get("user") if request is not None and hasattr(request, "scope") else None
        return getattr(user, "access_token", None)

    async def _client_name(self, client_id: str) -> str:
        cached = self._client_names.get(client_id)
        if cached and cached[1] > time.time():
            return cached[0]
        name = client_id
        client = await self.provider.get_client(client_id)
        if client is not None and client.client_name:
            name = client.client_name[:80]
        self._client_names[client_id] = (name, time.time() + 300)
        return name

    async def _authorize(self, ctx: Context, scope: str) -> tuple[AccessToken | None, CallToolResult | None]:
        token = self._token(ctx)
        if token is None:
            return None, _error("unauthorized", "Нет действующего OAuth-токена.")
        if scope not in token.scopes:
            return token, _error(
                "forbidden",
                f"У подключения нет права {scope}. Переподключите плагин в ChatGPT и разрешите "
                "«Управление телефоном» на странице согласия.",
            )
        return token, None

    async def _run(self, ctx: Context, tool: str, args: dict[str, Any], device_id: str | None) -> CallToolResult:
        token, denial = await self._authorize(ctx, required_scope(tool))
        if denial is not None:
            return denial
        assert token is not None
        client_name = await self._client_name(token.client_id)
        self.service.last_mcp_call = {"at": time.time(), "client": client_name, "tool": tool}
        clean_args = {k: v for k, v in args.items() if v is not None}
        try:
            outcome = await self.service.submit(
                tool,
                clean_args,
                device_id=device_id,
                source="mcp",
                client_id=token.client_id,
                client_name=client_name,
            )
        except CommandError as exc:
            return _error(exc.code, exc.message)
        return self.render(outcome)

    # --- представление результатов -----------------------------------------------
    def render(self, outcome: dict[str, Any]) -> CallToolResult:
        status = outcome.get("status")
        if status == "pending":
            structured = {k: outcome.get(k) for k in ("status", "command_id", "device_id", "tool", "state")}
            text = f"⏳ {outcome.get('message')}\ncommand_id: {outcome.get('command_id')}"
            return CallToolResult(content=[TextContent(type="text", text=text)], structured_content=structured)
        if status != "succeeded":
            error = outcome.get("error") or {}
            return _error(
                error.get("code") or status or "failed",
                error.get("message") or "Команда не выполнена",
                {"status": status, "command_id": outcome.get("command_id"), "tool": outcome.get("tool")},
            )
        tool = outcome.get("tool")
        data = outcome.get("data") or {}
        meta = {"status": status, "command_id": outcome.get("command_id"), "device_id": outcome.get("device_id")}
        if tool == "capture_screen":
            return self._render_capture(outcome, data, meta)
        if tool == "get_screen_info":
            text = format_screen(data)
        elif tool == "run_sequence":
            text = format_sequence(data)
        else:
            text = json.dumps(data, ensure_ascii=False, indent=1)[:20000]
        structured = {**meta, "data": data}
        return CallToolResult(content=[TextContent(type="text", text=text)], structured_content=structured)

    def _render_capture(self, outcome: dict[str, Any], data: dict[str, Any], meta: dict[str, Any]) -> CallToolResult:
        image = outcome.get("image") or {}
        mime = image.get("mime") if image.get("mime") in ("image/jpeg", "image/png") else None
        b64 = image.get("data") if isinstance(image.get("data"), str) else None
        width, height = data.get("width"), data.get("height")
        lines = [f"Скриншот {width}×{height}, приложение: {data.get('app_label') or data.get('package') or '?'}."]
        structured: dict[str, Any] = {**meta, "width": width, "height": height, "package": data.get("package")}
        content: list[Any] = []
        if b64 and mime:
            if self.settings.screenshot_links:
                token = self.screenshots.put(b64, mime)
                url = f"{self.settings.public_url}/s/{token}"
                ttl = self.settings.screenshot_link_ttl
                lines.append(f"Ссылка для просмотра (действует {ttl // 60} мин): {url}")
                structured["url"] = url
                structured["expires_in"] = ttl
            content.append(ImageContent(type="image", data=b64, mime_type=mime))
        else:
            lines.append("Изображение не получено.")
        if data.get("note"):
            lines.append(str(data["note"]))
        lines.append("Подсказка: структуру экрана с id элементов даёт get_screen_info.")
        content.insert(0, TextContent(type="text", text="\n".join(lines)))
        return CallToolResult(content=content, structured_content=structured)

    # --- инструменты ---------------------------------------------------------------
    def _register_tools(self) -> None:  # noqa: C901 - список инструментов
        bridge = self

        async def get_device_status(
            ctx: Context,
            include_apps: Annotated[bool, Field(description="Добавить список установленных приложений")] = False,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(ctx, "get_device_status", {"include_apps": include_apps}, device_id)

        async def get_screen_info(
            ctx: Context,
            max_elements: Annotated[int, Field(ge=10, le=400, description="Максимум элементов в ответе")] = 150,
            include_invisible: Annotated[bool, Field(description="Включить невидимые элементы")] = False,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx, "get_screen_info", {"max_elements": max_elements, "include_invisible": include_invisible}, device_id
            )

        async def capture_screen(
            ctx: Context,
            max_dimension: Annotated[int, Field(ge=320, le=2560, description="Длинная сторона, px")] = 1280,
            quality: Annotated[int, Field(ge=30, le=95, description="Качество JPEG")] = 70,
            format: Annotated[Literal["jpeg", "png"], Field(description="Формат изображения")] = "jpeg",  # noqa: A002
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx, "capture_screen", {"max_dimension": max_dimension, "quality": quality, "format": format}, device_id
            )

        async def open_app(
            ctx: Context,
            package_name: Annotated[str | None, Field(max_length=200, pattern=PACKAGE_NAME, description="Имя пакета, например org.telegram.messenger")] = None,
            app_name: Annotated[str | None, Field(min_length=1, max_length=100, description="Название приложения, как в лаунчере")] = None,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(ctx, "open_app", {"package_name": package_name, "app_name": app_name}, device_id)

        async def tap_element(
            ctx: Context,
            element_id: Annotated[str | None, Field(pattern=ELEMENT_ID, description="id элемента из get_screen_info, например e12")] = None,
            snapshot_id: Annotated[str | None, Field(pattern=SNAPSHOT_ID, description="snapshot_id из того же get_screen_info")] = None,
            text: Annotated[str | None, Field(min_length=1, max_length=200, description="Текст или описание элемента")] = None,
            exact: Annotated[bool, Field(description="Точное совпадение текста")] = False,
            index: Annotated[int, Field(ge=0, le=50, description="Номер совпадения по тексту")] = 0,
            x: Annotated[int | None, Field(ge=0, le=10000, description="Координата X в пикселях (крайний случай)")] = None,
            y: Annotated[int | None, Field(ge=0, le=10000, description="Координата Y в пикселях")] = None,
            long_press: Annotated[bool, Field(description="Долгое нажатие")] = False,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx,
                "tap_element",
                {
                    "element_id": element_id,
                    "snapshot_id": snapshot_id,
                    "text": text,
                    "exact": exact,
                    "index": index,
                    "x": x,
                    "y": y,
                    "long_press": long_press,
                },
                device_id,
            )

        async def swipe_screen(
            ctx: Context,
            direction: Annotated[Literal["up", "down", "left", "right"], Field(description="Направление движения пальца: up — пролистать вниз к следующему содержимому")],
            distance: Annotated[float, Field(ge=0.1, le=1.0, description="Доля экрана")] = 0.5,
            duration_ms: Annotated[int, Field(ge=50, le=3000, description="Длительность жеста, мс")] = 300,
            element_id: Annotated[str | None, Field(pattern=ELEMENT_ID, description="Прокрутить конкретный прокручиваемый элемент")] = None,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx,
                "swipe_screen",
                {"direction": direction, "distance": distance, "duration_ms": duration_ms, "element_id": element_id},
                device_id,
            )

        async def input_text(
            ctx: Context,
            text: Annotated[str, Field(max_length=2000, description="Текст для ввода")],
            element_id: Annotated[str | None, Field(pattern=ELEMENT_ID, description="Поле ввода; по умолчанию — поле в фокусе")] = None,
            mode: Annotated[Literal["replace", "append"], Field(description="Заменить или дописать")] = "replace",
            submit: Annotated[bool, Field(description="Нажать «Ввод/Отправить» на клавиатуре (требует подтверждения)")] = False,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx, "input_text", {"text": text, "element_id": element_id, "mode": mode, "submit": submit}, device_id
            )

        async def press_back(ctx: Context, device_id: DeviceIdArg = None) -> CallToolResult:
            return await bridge._run(ctx, "press_back", {}, device_id)

        async def press_home(ctx: Context, device_id: DeviceIdArg = None) -> CallToolResult:
            return await bridge._run(ctx, "press_home", {}, device_id)

        async def search_files(
            ctx: Context,
            query: Annotated[str, Field(min_length=1, max_length=200, description="Часть имени или маска (* и ?)")],
            folder: Annotated[str | None, Field(min_length=1, max_length=200, description="Имя разрешённой папки")] = None,
            max_results: Annotated[int, Field(ge=1, le=100)] = 30,
            mime_prefix: Annotated[str | None, Field(max_length=60, pattern=r"^[a-z]+(/[a-z0-9.+\-]*)?$", description="Например image/ или application/pdf")] = None,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx,
                "search_files",
                {"query": query, "folder": folder, "max_results": max_results, "mime_prefix": mime_prefix},
                device_id,
            )

        async def open_file(
            ctx: Context,
            file_id: Annotated[str, Field(pattern=FILE_ID, description="file_id из результата search_files")],
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(ctx, "open_file", {"file_id": file_id}, device_id)

        async def get_notifications(
            ctx: Context,
            limit: Annotated[int, Field(ge=1, le=100)] = 20,
            package_name: Annotated[str | None, Field(max_length=200, pattern=PACKAGE_NAME)] = None,
            include_ongoing: Annotated[bool, Field(description="Включать постоянные уведомления")] = False,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(
                ctx,
                "get_notifications",
                {"limit": limit, "package_name": package_name, "include_ongoing": include_ongoing},
                device_id,
            )

        async def run_sequence(
            ctx: Context,
            steps: Annotated[
                list[dict[str, Any]],
                Field(
                    min_length=1,
                    max_length=10,
                    description="Шаги: [{\"tool\": \"tap_element\", \"args\": {...}, \"wait_ms\": 500}]. "
                    "Допустимые tool: open_app, tap_element, swipe_screen, input_text, press_back, press_home, "
                    "get_screen_info.",
                ),
            ],
            stop_on_error: bool = True,
            device_id: DeviceIdArg = None,
        ) -> CallToolResult:
            return await bridge._run(ctx, "run_sequence", {"steps": steps, "stop_on_error": stop_on_error}, device_id)

        async def list_devices(ctx: Context) -> CallToolResult:
            _, denial = await bridge._authorize(ctx, SCOPE_READ)
            if denial is not None:
                return denial
            devices = await bridge.store.devices_list()
            items = []
            for d in devices:
                items.append(
                    {
                        "device_id": d["id"],
                        "name": d["name"],
                        "model": d.get("model"),
                        "android": d.get("android_version"),
                        "online": bridge.service.hub.is_online(d["id"]),
                        "last_seen_at": d.get("last_seen_at"),
                    }
                )
            text = json.dumps(items, ensure_ascii=False, indent=1) if items else "Нет сопряжённых устройств."
            return CallToolResult(content=[TextContent(type="text", text=text)], structured_content={"devices": items})

        async def get_command_result(
            ctx: Context,
            command_id: Annotated[str, Field(pattern=COMMAND_ID, description="command_id из ответа со статусом pending")],
            wait_seconds: Annotated[int, Field(ge=0, le=50, description="Сколько секунд подождать завершения")] = 20,
        ) -> CallToolResult:
            _, denial = await bridge._authorize(ctx, SCOPE_READ)
            if denial is not None:
                return denial
            try:
                outcome = await bridge.service.get_result(command_id, wait=float(wait_seconds))
            except CommandError as exc:
                return _error(exc.code, exc.message)
            return bridge.render(outcome)

        self._add(get_device_status, title="Состояние устройства", annotations=READ_ONLY, description=(
            "Модель, версия Android, батарея, сеть, активное приложение, блокировка экрана и выданные разрешения."
        ))
        self._add(get_screen_info, title="Что на экране", annotations=READ_ONLY, description=(
            "Список элементов текущего экрана: id (e1, e2…), тип, текст, флаги и координаты. Пароли скрыты. "
            "Основной способ понять, что на экране, перед нажатием или вводом."
        ))
        self._add(capture_screen, title="Скриншот", annotations=READ_ONLY, description=(
            "Снимок экрана через MediaProjection. При первом вызове после разблокировки пользователь "
            "подтверждает захват экрана на телефоне. Защищённые окна (FLAG_SECURE) получаются чёрными."
        ))
        self._add(open_app, title="Открыть приложение", annotations=ACTION, description=(
            "Открыть или переключиться на приложение по имени пакета или названию. Банковские приложения и "
            "менеджеры паролей заблокированы."
        ))
        self._add(tap_element, title="Нажать", annotations=RISKY_ACTION, description=(
            "Нажать на элемент: по element_id (+snapshot_id) из get_screen_info, по тексту или по координатам. "
            "Кнопки отправки, удаления, оплаты и т. п. требуют подтверждения на телефоне."
        ))
        self._add(swipe_screen, title="Прокрутка", annotations=ACTION, description=(
            "Прокрутка жестом или прокрутка конкретного элемента. direction — направление движения пальца."
        ))
        self._add(input_text, title="Ввести текст", annotations=RISKY_ACTION, description=(
            "Ввести текст в поле (по element_id или в поле в фокусе). В поля паролей ввод запрещён. "
            "submit=true нажимает «Ввод» и требует подтверждения."
        ))
        self._add(press_back, title="Назад", annotations=ACTION, description="Системная кнопка «Назад».")
        self._add(press_home, title="Домой", annotations=ACTION, description="Системная кнопка «Домой».")
        self._add(search_files, title="Поиск файлов", annotations=READ_ONLY, description=(
            "Поиск файлов по имени только в папках, которые пользователь разрешил в приложении."
        ))
        self._add(open_file, title="Открыть файл", annotations=ACTION, description=(
            "Открыть найденный файл в подходящем приложении на телефоне."
        ))
        self._add(get_notifications, title="Уведомления", annotations=READ_ONLY, description=(
            "Текущие и недавние уведомления (если пользователь дал доступ). Коды подтверждения маскируются."
        ))
        self._add(run_sequence, title="Последовательность", annotations=RISKY_ACTION, description=(
            "Выполнить до 10 шагов подряд на телефоне. Каждый шаг проходит те же проверки и подтверждения."
        ))
        self._add(list_devices, title="Устройства", annotations=READ_ONLY, description=(
            "Список сопряжённых телефонов и их статус онлайн."
        ))
        self._add(get_command_result, title="Результат команды", annotations=READ_ONLY, description=(
            "Получить результат команды, вернувшей статус pending (например, ожидавшей подтверждения)."
        ))


def _error(code: str, message: str, extra: dict[str, Any] | None = None) -> CallToolResult:
    structured = {"status": "error", "error": {"code": code, "message": message}}
    if extra:
        structured.update({k: v for k, v in extra.items() if v is not None})
    return CallToolResult(
        content=[TextContent(type="text", text=f"Ошибка [{code}]: {message}")],
        structured_content=structured,
        is_error=True,
    )


def _quote(value: Any, limit: int = 80) -> str:
    text = str(value).replace("\n", " ").strip()
    if len(text) > limit:
        text = text[: limit - 1] + "…"
    return json.dumps(text, ensure_ascii=False)


def format_screen(data: dict[str, Any]) -> str:
    screen = data.get("screen") or {}
    header = (
        f"Экран: {data.get('app_label') or '?'} ({data.get('package') or '?'}) · "
        f"{screen.get('w', '?')}×{screen.get('h', '?')} · snapshot_id={data.get('snapshot_id')}"
    )
    lines = [header]
    if data.get("keyboard"):
        lines.append("Клавиатура открыта.")
    for el in data.get("elements") or []:
        bounds = el.get("b") or [0, 0, 0, 0]
        cx = (bounds[0] + bounds[2]) // 2 if len(bounds) == 4 else "?"
        cy = (bounds[1] + bounds[3]) // 2 if len(bounds) == 4 else "?"
        parts = [str(el.get("id")), str(el.get("cls") or "View")]
        if el.get("text"):
            parts.append(_quote(el["text"]))
        if el.get("desc") and el.get("desc") != el.get("text"):
            parts.append(f"desc={_quote(el['desc'])}")
        if el.get("rid"):
            parts.append(f"#{el['rid']}")
        if el.get("f"):
            parts.append("[" + ",".join(el["f"]) + "]")
        parts.append(f"@({cx},{cy})")
        lines.append(" ".join(parts))
    if data.get("truncated"):
        lines.append(f"… показаны не все элементы (всего {data.get('element_count')}). Увеличьте max_elements.")
    return "\n".join(lines)


def format_sequence(data: dict[str, Any]) -> str:
    lines = [f"Последовательность: выполнено шагов {data.get('completed', 0)} из {data.get('total', 0)}."]
    for step in data.get("steps") or []:
        mark = "✓" if step.get("ok") else "✗"
        detail = step.get("error", {}).get("message") if isinstance(step.get("error"), dict) else ""
        lines.append(f"{mark} {step.get('index')}. {step.get('tool')} {detail or ''}".rstrip())
        if step.get("tool") == "get_screen_info" and isinstance(step.get("data"), dict):
            lines.append(format_screen(step["data"]))
    return "\n".join(lines)
