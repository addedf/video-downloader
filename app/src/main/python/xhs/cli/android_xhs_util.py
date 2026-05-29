import json
from re import DOTALL, compile

from source.expansion import Converter
from yaml import safe_load
from source.module.tools import print as rich_print


class AndroidConverter(Converter):
    INITIAL_STATE_PREFIX = "window.__INITIAL_STATE__="
    NULL_CHAR = "\x00"
    BOM_CHARS = "\ufeff\ufffe"
    INITIAL_STATE = compile(
        r"window\.__INITIAL_STATE__\s*=\s*(.*?)(?:</script>|<script|\Z)",
        DOTALL,
    )

    def _extract_object(self, html: str) -> str:
        html = self._clean_text(html)
        if not html:
            return ""
        if match := self.INITIAL_STATE.search(html):
            return f"{self.INITIAL_STATE_PREFIX}{match.group(1).strip()}"
        try:
            return super()._extract_object(html)
        except Exception:
            return ""

    @classmethod
    def _convert_object(cls, text: str) -> dict:
        cleaned = cls._clean_text(text)
        if cleaned.startswith(cls.INITIAL_STATE_PREFIX):
            cleaned = cleaned.removeprefix(cls.INITIAL_STATE_PREFIX)
        cleaned = cls.YAML_ILLEGAL.sub("", cleaned).strip().removesuffix(";")
        if not cleaned:
            return {}
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            pass
        try:
            return safe_load(cleaned) or {}
        except Exception:
            return {}

    @staticmethod
    def _clean_text(value: str) -> str:
        return str(value or "").replace(
            AndroidConverter.NULL_CHAR,
            "",
        ).lstrip(AndroidConverter.BOM_CHARS)


class Print:
    def __init__(self, func=rich_print):
        self.func = func

    def __call__(self):
        return self.func
