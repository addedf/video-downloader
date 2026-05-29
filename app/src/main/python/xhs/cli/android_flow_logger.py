import logging
import time
from contextlib import contextmanager
from typing import Any, Dict, Iterator


class AndroidFlowLogger:
    def __init__(self) -> None:
        self.timings: Dict[str, int] = {}
        self._started = time.perf_counter()
        self._logger = logging.getLogger("XHSAndroid")

    @contextmanager
    def stage(self, name: str, **fields: Any) -> Iterator[None]:
        started = time.perf_counter()
        self.info(f"{name}.begin", **fields)
        try:
            yield
        finally:
            elapsed = int((time.perf_counter() - started) * 1000)
            self.timings[f"{name}_ms"] = elapsed
            self.info(f"{name}.done", duration_ms=elapsed)

    def mark_total(self) -> None:
        self.timings["total_ms"] = int((time.perf_counter() - self._started) * 1000)

    def info(self, event: str, **fields: Any) -> None:
        self._logger.info("%s %s", event, fields)

    def warning(self, event: str, **fields: Any) -> None:
        self._logger.warning("%s %s", event, fields)

    def error(self, event: str, **fields: Any) -> None:
        self._logger.error("%s %s", event, fields)


def url_preview(url: str, limit: int = 120) -> str:
    text = str(url or "").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit] + "..."
