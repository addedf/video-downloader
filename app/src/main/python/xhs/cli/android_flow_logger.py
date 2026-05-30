import logging
import sys
import time
from contextlib import contextmanager
from typing import Any, Dict, Iterator


class AndroidFlowLogger:
    def __init__(self, name: str = "XhsAndroidFlow") -> None:
        self.timings: Dict[str, int] = {}
        self._started = time.perf_counter()
        self._logger = logging.getLogger(name)
        self._logger.setLevel(logging.INFO)
        self._logger.propagate = False
        if not self._logger.handlers:
            handler = logging.StreamHandler(sys.stderr)
            handler.setLevel(logging.INFO)
            handler.setFormatter(
                logging.Formatter(
                    "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
                    datefmt="%Y-%m-%d %H:%M:%S",
                )
            )
            self._logger.addHandler(handler)

    @contextmanager
    def stage(self, name: str, **fields: Any) -> Iterator[None]:
        started = time.perf_counter()
        self.info(f"{name}.start", **fields)
        try:
            yield
        except Exception as exc:
            elapsed = int((time.perf_counter() - started) * 1000)
            self.timings[f"{name}_ms"] = elapsed
            self.error(
                f"{name}.failed",
                duration_ms=elapsed,
                error=type(exc).__name__,
                message=str(exc),
            )
            raise
        finally:
            if f"{name}_ms" not in self.timings:
                elapsed = int((time.perf_counter() - started) * 1000)
                self.timings[f"{name}_ms"] = elapsed
                self.info(f"{name}.done", duration_ms=elapsed)

    def mark_total(self) -> None:
        self.timings["total_ms"] = int((time.perf_counter() - self._started) * 1000)

    def info(self, event: str, **fields: Any) -> None:
        self._logger.info("%s%s", event, self._format_fields(fields))

    def warning(self, event: str, **fields: Any) -> None:
        self._logger.warning("%s%s", event, self._format_fields(fields))

    def error(self, event: str, **fields: Any) -> None:
        self._logger.error("%s%s", event, self._format_fields(fields))

    @staticmethod
    def _format_fields(fields: Dict[str, Any]) -> str:
        cleaned = {
            key: value
            for key, value in fields.items()
            if value is not None and value != ""
        }
        if not cleaned:
            return ""
        parts = [f"{key}={AndroidFlowLogger._safe_value(value)}" for key, value in cleaned.items()]
        return " | " + " ".join(parts)

    @staticmethod
    def _safe_value(value: Any) -> str:
        text = str(value).replace("\n", "\\n").replace("\r", "\\r")
        if len(text) > 180:
            return text[:177] + "..."
        return text


def url_preview(url: str, limit: int = 120) -> str:
    text = str(url or "").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit] + "..."
