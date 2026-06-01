import time


class AndroidProgressReporter:
    _MIN_PROGRESS_INTERVAL_SECONDS = 0.3
    _MIN_PROGRESS_PERCENT_DELTA = 1

    def __init__(self, progress_callback=None):
        self.progress_callback = progress_callback
        self._last_percent = -1
        self._last_reported_at = 0.0

    def on_file_progress(
        self,
        downloaded_bytes: int,
        total_bytes: int,
        speed_bytes_per_second: int,
        *,
        force: bool = False,
    ) -> None:
        if self.progress_callback is None:
            return

        downloaded_bytes = max(0, int(downloaded_bytes or 0))
        total_bytes = max(0, int(total_bytes or 0))
        speed_bytes_per_second = max(0, int(speed_bytes_per_second or 0))
        percent = self._calculate_percent(downloaded_bytes, total_bytes)
        if not force and not self._should_report(percent, total_bytes):
            return

        self._last_percent = percent
        self._last_reported_at = time.monotonic()
        try:
            self.progress_callback.onProgress(
                percent,
                downloaded_bytes,
                total_bytes,
                speed_bytes_per_second,
            )
        except Exception:
            return None

    @classmethod
    def _calculate_percent(cls, downloaded_bytes: int, total_bytes: int) -> int:
        if total_bytes <= 0:
            return 0
        return max(0, min(100, int(downloaded_bytes * 100 / total_bytes)))

    def _should_report(self, percent: int, total_bytes: int) -> bool:
        now = time.monotonic()
        if total_bytes <= 0:
            return now - self._last_reported_at >= self._MIN_PROGRESS_INTERVAL_SECONDS
        return (
            percent - self._last_percent >= self._MIN_PROGRESS_PERCENT_DELTA
            or now - self._last_reported_at >= self._MIN_PROGRESS_INTERVAL_SECONDS
        )
