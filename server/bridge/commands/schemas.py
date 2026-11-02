"""Строгие схемы аргументов команд. Это единый источник правды для MCP,
REST и тестов. Неизвестные поля запрещены, все строки и числа ограничены.

Телефон повторно проверяет аргументы своей собственной схемой: доверять
только серверу нельзя.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

ELEMENT_ID = r"^e[0-9]{1,4}$"
SNAPSHOT_ID = r"^s_[A-Za-z0-9]{4,32}$"
PACKAGE_NAME = r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$"
FILE_ID = r"^f_[A-Za-z0-9]{8,40}$"
DEVICE_ID = r"^dev_[A-Za-z0-9]{8,40}$"
COMMAND_ID = r"^cmd_[A-Za-z0-9]{8,40}$"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


def _no_control_chars(value: str, allow_newlines: bool = False) -> str:
    for ch in value:
        code = ord(ch)
        if code == 0 or (code < 32 and not (allow_newlines and ch in "\n\t")):
            raise ValueError("control characters are not allowed")
    return value


class GetDeviceStatusArgs(Strict):
    include_apps: bool = False


class GetScreenInfoArgs(Strict):
    max_elements: int = Field(150, ge=10, le=400)
    include_invisible: bool = False


class CaptureScreenArgs(Strict):
    max_dimension: int = Field(1280, ge=320, le=2560)
    quality: int = Field(70, ge=30, le=95)
    format: Literal["jpeg", "png"] = "jpeg"


class OpenAppArgs(Strict):
    package_name: str | None = Field(None, max_length=200, pattern=PACKAGE_NAME)
    app_name: str | None = Field(None, min_length=1, max_length=100)

    @field_validator("app_name")
    @classmethod
    def _app_name(cls, value: str | None) -> str | None:
        return None if value is None else _no_control_chars(value)

    @model_validator(mode="after")
    def _exactly_one(self) -> OpenAppArgs:
        if (self.package_name is None) == (self.app_name is None):
            raise ValueError("specify exactly one of package_name or app_name")
        return self


class TapElementArgs(Strict):
    element_id: str | None = Field(None, pattern=ELEMENT_ID)
    snapshot_id: str | None = Field(None, pattern=SNAPSHOT_ID)
    text: str | None = Field(None, min_length=1, max_length=200)
    exact: bool = False
    index: int = Field(0, ge=0, le=50)
    x: int | None = Field(None, ge=0, le=10000)
    y: int | None = Field(None, ge=0, le=10000)
    long_press: bool = False

    @field_validator("text")
    @classmethod
    def _text(cls, value: str | None) -> str | None:
        return None if value is None else _no_control_chars(value)

    @model_validator(mode="after")
    def _target(self) -> TapElementArgs:
        targets = [self.element_id is not None, self.text is not None, self.x is not None or self.y is not None]
        if sum(targets) != 1:
            raise ValueError("specify exactly one target: element_id, text, or x+y")
        if (self.x is None) != (self.y is None):
            raise ValueError("x and y must be given together")
        if self.snapshot_id is not None and self.element_id is None:
            raise ValueError("snapshot_id is only valid with element_id")
        return self


class SwipeScreenArgs(Strict):
    direction: Literal["up", "down", "left", "right"]
    distance: float = Field(0.5, ge=0.1, le=1.0)
    duration_ms: int = Field(300, ge=50, le=3000)
    element_id: str | None = Field(None, pattern=ELEMENT_ID)


class InputTextArgs(Strict):
    text: str = Field(..., max_length=2000)
    element_id: str | None = Field(None, pattern=ELEMENT_ID)
    mode: Literal["replace", "append"] = "replace"
    submit: bool = False

    @field_validator("text")
    @classmethod
    def _text(cls, value: str) -> str:
        return _no_control_chars(value, allow_newlines=True)


class PressBackArgs(Strict):
    pass


class PressHomeArgs(Strict):
    pass


class SearchFilesArgs(Strict):
    query: str = Field(..., min_length=1, max_length=200)
    folder: str | None = Field(None, min_length=1, max_length=200)
    max_results: int = Field(30, ge=1, le=100)
    mime_prefix: str | None = Field(None, max_length=60, pattern=r"^[a-z]+(/[a-z0-9.+\-]*)?$")

    @field_validator("query", "folder")
    @classmethod
    def _plain(cls, value: str | None) -> str | None:
        return None if value is None else _no_control_chars(value)


class OpenFileArgs(Strict):
    file_id: str = Field(..., pattern=FILE_ID)


class GetNotificationsArgs(Strict):
    limit: int = Field(20, ge=1, le=100)
    package_name: str | None = Field(None, max_length=200, pattern=PACKAGE_NAME)
    include_ongoing: bool = False


SEQUENCE_TOOLS = (
    "open_app",
    "tap_element",
    "swipe_screen",
    "input_text",
    "press_back",
    "press_home",
    "get_screen_info",
)


class SequenceStep(Strict):
    tool: Literal["open_app", "tap_element", "swipe_screen", "input_text", "press_back", "press_home", "get_screen_info"]
    args: dict[str, Any] = Field(default_factory=dict)
    wait_ms: int = Field(0, ge=0, le=5000)

    @model_validator(mode="after")
    def _validate_args(self) -> SequenceStep:
        model = TOOLS[self.tool].model
        # Нормализуем аргументы шага его собственной схемой.
        normalized = model.model_validate(self.args).model_dump(exclude_none=True)
        object.__setattr__(self, "args", normalized)
        return self


class RunSequenceArgs(Strict):
    steps: list[SequenceStep] = Field(..., min_length=1, max_length=10)
    stop_on_error: bool = True


@dataclass(frozen=True)
class ToolSpec:
    name: str
    model: type[Strict]
    scope: Literal["read", "control"]
    device: bool = True  # команда исполняется на телефоне


TOOLS: dict[str, ToolSpec] = {
    spec.name: spec
    for spec in [
        ToolSpec("get_device_status", GetDeviceStatusArgs, "read"),
        ToolSpec("get_screen_info", GetScreenInfoArgs, "read"),
        ToolSpec("capture_screen", CaptureScreenArgs, "read"),
        ToolSpec("get_notifications", GetNotificationsArgs, "read"),
        ToolSpec("search_files", SearchFilesArgs, "read"),
        ToolSpec("open_app", OpenAppArgs, "control"),
        ToolSpec("tap_element", TapElementArgs, "control"),
        ToolSpec("swipe_screen", SwipeScreenArgs, "control"),
        ToolSpec("input_text", InputTextArgs, "control"),
        ToolSpec("press_back", PressBackArgs, "control"),
        ToolSpec("press_home", PressHomeArgs, "control"),
        ToolSpec("open_file", OpenFileArgs, "control"),
        ToolSpec("run_sequence", RunSequenceArgs, "control"),
    ]
}

SCOPE_READ = "device.read"
SCOPE_CONTROL = "device.control"
ALL_SCOPES = [SCOPE_READ, SCOPE_CONTROL]


def required_scope(tool: str) -> str:
    return SCOPE_CONTROL if TOOLS[tool].scope == "control" else SCOPE_READ


class ArgumentError(ValueError):
    """Аргументы не прошли проверку."""


def validate_args(tool: str, args: dict[str, Any] | None) -> dict[str, Any]:
    """Проверить аргументы и вернуть нормализованный словарь без None."""
    spec = TOOLS.get(tool)
    if spec is None:
        raise ArgumentError(f"unknown tool: {tool}")
    if args is None:
        args = {}
    if not isinstance(args, dict):
        raise ArgumentError("arguments must be an object")
    try:
        model = spec.model.model_validate(args)
    except ValidationError as exc:
        problems = "; ".join(
            f"{'.'.join(str(p) for p in err['loc']) or 'args'}: {err['msg']}" for err in exc.errors()
        )
        raise ArgumentError(problems) from exc
    data = model.model_dump(exclude_none=True)
    if tool == "run_sequence":
        data["steps"] = [
            {"tool": step.tool, "args": step.args, "wait_ms": step.wait_ms} for step in model.steps  # type: ignore[attr-defined]
        ]
    return data
