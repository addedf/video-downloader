import logging
import sys
import time
from contextlib import contextmanager
from typing import Any, Dict, Iterator, Optional


class AndroidFlowLogger:
    def __init__(self, name: str = "AndroidDownloadFlow"):
        self.logger = logging.getLogger(name)
        self.logger.setLevel(logging.INFO)
        self.logger.propagate = False
        self.timings: Dict[str, int] = {}
        self._started_at = time.perf_counter()
        if not self.logger.handlers:
            handler = logging.StreamHandler(sys.stderr)
            handler.setLevel(logging.INFO)
            handler.setFormatter(
                logging.Formatter(
                    "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
                    datefmt="%Y-%m-%d %H:%M:%S",
                )
            )
            self.logger.addHandler(handler)

    def info(self, event: str, **fields: Any) -> None:
        self.logger.info("%s%s", event, self._format_fields(fields))

    def warning(self, event: str, **fields: Any) -> None:
        self.logger.warning("%s%s", event, self._format_fields(fields))

    def error(self, event: str, **fields: Any) -> None:
        self.logger.error("%s%s", event, self._format_fields(fields))

    @contextmanager
    def stage(self, name: str, **fields: Any) -> Iterator[None]:
        started_at = time.perf_counter()
        self.info(f"{name}.start", **fields)
        try:
            yield
        except Exception as exc:
            duration_ms = self._elapsed_ms(started_at)
            self.timings[f"{name}_ms"] = duration_ms
            self.error(
                f"{name}.failed",
                duration_ms=duration_ms,
                error=type(exc).__name__,
                message=str(exc),
            )
            raise
        else:
            duration_ms = self._elapsed_ms(started_at)
            self.timings[f"{name}_ms"] = duration_ms
            self.info(f"{name}.done", duration_ms=duration_ms)

    def mark_total(self) -> None:
        self.timings["total_ms"] = self._elapsed_ms(self._started_at)

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        return max(0, int((time.perf_counter() - started_at) * 1000))

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


def new_flow_logger() -> AndroidFlowLogger:
    return AndroidFlowLogger()


def url_preview(value: Optional[str]) -> str:
    text = str(value or "").strip()
    if len(text) <= 120:
        return text
    return text[:117] + "..."
