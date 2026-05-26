from typing import Optional

from auth import CookieManager
from config import ConfigLoader
from storage import Database


class AndroidProgressReporter:
    def __init__(self):
        self.step = ""
        self.detail = ""
        self.total_items = 0
        self.finished_items = 0

    def update_step(self, step: str, detail: str = "") -> None:
        self.step = step
        self.detail = detail

    def set_item_total(self, total: int, detail: str = "") -> None:
        self.total_items = int(total or 0)
        self.detail = detail

    def advance_item(self, status: str, detail: str = "") -> None:
        self.finished_items += 1
        self.detail = f"{status}: {detail}" if detail else status

    def on_author(self, nickname: Optional[str] = None, sec_uid: Optional[str] = None) -> None:
        return None

class AndroidGlobalConfig:
    def __init__(self):
        self.config_loader: Optional[ConfigLoader] = None
        self.cookie_manager: Optional[CookieManager] = None
        self.database: Optional[Database] = None
        self.androidProgressReporter: AndroidProgressReporter = AndroidProgressReporter()

