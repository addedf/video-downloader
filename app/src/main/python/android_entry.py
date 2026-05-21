import asyncio
import json
import os
import re
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

import aiofiles
import aiohttp

os.environ.setdefault("PYTHONUTF8", "1")

from auth import CookieManager
from config import ConfigLoader
from control import QueueManager, RateLimiter, RetryHandler
from core import DouyinAPIClient, DownloaderFactory, URLParser
from storage import Database, FileManager
from utils.cookie_utils import parse_cookie_header, sanitize_cookies
from utils.validators import is_short_url, normalize_short_url

_FILE_MANAGER_PATCHED = False
_ANDROID_DOWNLOAD_CHUNK_SIZE = 256 * 1024
_ANDROID_THREAD_COUNT = 4
_ANDROID_RATE_LIMIT = 3.0


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


def download(input_text: str, cookie_header: str, output_dir: str, app_data_dir: str) -> str:
    try:
        result = asyncio.run(_download_async(input_text, cookie_header, output_dir, app_data_dir))
    except Exception as exc:
        result = {
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(limit=12),
            "files": [],
            "output_dir": output_dir,
        }
    return json.dumps(result, ensure_ascii=False)


def warm_up(app_data_dir: str) -> str:
    try:
        _prepare_runtime(Path(app_data_dir))
        return json.dumps({"ok": True}, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


async def _download_async(
    input_text: str,
    cookie_header: str,
    output_dir: str,
    app_data_dir: str,
) -> Dict[str, Any]:
    started_perf = time.perf_counter()
    started_wall = time.time()
    timings: Dict[str, int] = {}

    app_root = Path(app_data_dir)
    output_root = Path(output_dir)
    app_root.mkdir(parents=True, exist_ok=True)
    output_root.mkdir(parents=True, exist_ok=True)

    _prepare_runtime(app_root)
    _mark_timing(timings, "prepare_ms", started_perf)
    cookies = _parse_cookies(cookie_header)
    if not cookies:
        return _error("请先登录抖音获取 Cookie", output_root, timings)

    url = str(input_text or "").strip()
    if not url:
        return _error("请先粘贴抖音分享文本或链接", output_root, timings)

    config = _build_config(url, cookies, output_root, app_root)
    cookie_manager = CookieManager(str(app_root / ".cookies.json"))
    cookie_manager.set_cookies(cookies)

    _mark_timing(timings, "config_ms", started_perf)
    database = Database(db_path=str(app_root / "dy_downloader.db"))
    await database.initialize()
    _mark_timing(timings, "database_ms", started_perf)

    reporter = AndroidProgressReporter()
    try:
        async with DouyinAPIClient(cookie_manager.get_cookies(), proxy=config.get("proxy")) as api_client:
            resolved_url = await _resolve_input_url(url, api_client)
            _mark_timing(timings, "resolve_ms", started_perf)
            parsed = URLParser.parse(resolved_url)
            if not parsed:
                return _error(f"无法识别链接类型: {url}", output_root, timings)

            file_manager = FileManager(str(output_root))
            downloader = DownloaderFactory.create(
                parsed["type"],
                config,
                api_client,
                file_manager,
                cookie_manager,
                database,
                RateLimiter(max_per_second=float(config.get("rate_limit", 2) or 2)),
                RetryHandler(max_retries=int(config.get("retry_times", 3) or 3)),
                QueueManager(max_workers=int(config.get("thread", 2) or 2)),
                progress_reporter=reporter,
            )
            if downloader is None:
                return _error(f"暂不支持的链接类型: {parsed.get('type')}", output_root, timings)

            result = await downloader.download(parsed)
            _mark_timing(timings, "download_ms", started_perf)
            files = _changed_files_since(output_root, started_wall)
            _mark_timing(timings, "collect_files_ms", started_perf)
            return {
                "ok": bool(result and result.success > 0),
                "url": resolved_url,
                "type": parsed.get("type"),
                "total": int(getattr(result, "total", 0) or 0),
                "success": int(getattr(result, "success", 0) or 0),
                "failed": int(getattr(result, "failed", 0) or 0),
                "skipped": int(getattr(result, "skipped", 0) or 0),
                "output_dir": str(output_root),
                "files": files,
                "message": _summary_message(result, files),
                "timings": timings,
            }
    finally:
        await database.close()


def _prepare_runtime(app_root: Path) -> None:
    os.environ.setdefault("HOME", str(app_root))
    os.environ.setdefault("TMPDIR", str(app_root / "tmp"))
    os.environ.setdefault("PYTHONUTF8", "1")
    Path(os.environ["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    _patch_file_manager_for_android()


def _patch_file_manager_for_android() -> None:
    global _FILE_MANAGER_PATCHED
    if _FILE_MANAGER_PATCHED:
        return

    async def download_file(
        self,
        url: str,
        save_path: Path,
        session: Optional[aiohttp.ClientSession] = None,
        headers: Optional[Dict[str, str]] = None,
        proxy: Optional[str] = None,
        *,
        prefer_response_content_type: bool = False,
        return_saved_path: bool = False,
    ):
        should_close = False
        if session is None:
            default_headers = headers or {
                "User-Agent": "Mozilla/5.0 (Linux; Android 15; Mobile) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36",
                "Referer": "https://www.douyin.com/",
                "Accept": "*/*",
            }
            session = aiohttp.ClientSession(headers=default_headers)
            should_close = True

        final_path = save_path
        tmp_path = save_path.with_suffix(save_path.suffix + ".tmp")
        try:
            async with session.get(
                url,
                timeout=aiohttp.ClientTimeout(total=240, sock_connect=20, sock_read=90),
                headers=headers,
                proxy=proxy or None,
            ) as response:
                if response.status != 200:
                    return False

                final_path = self._resolve_save_path_from_content_type(
                    save_path,
                    response.headers,
                    prefer_response_content_type=prefer_response_content_type,
                )
                tmp_path = final_path.with_suffix(final_path.suffix + ".tmp")
                expected_size = response.content_length
                written = 0
                async with aiofiles.open(tmp_path, "wb") as file:
                    async for chunk in response.content.iter_chunked(_ANDROID_DOWNLOAD_CHUNK_SIZE):
                        await file.write(chunk)
                        written += len(chunk)

                if expected_size is not None and written != expected_size:
                    tmp_path.unlink(missing_ok=True)
                    return False

                os.replace(str(tmp_path), str(final_path))
                return final_path if return_saved_path else True
        except Exception:
            tmp_path.unlink(missing_ok=True)
            return False
        finally:
            if should_close:
                await session.close()

    FileManager.download_file = download_file
    _FILE_MANAGER_PATCHED = True


def _parse_cookies(cookie_header: str) -> Dict[str, str]:
    if not cookie_header:
        return {}
    cookie_header = cookie_header.strip()
    try:
        if cookie_header.startswith("{"):
            raw = json.loads(cookie_header)
            if isinstance(raw, dict):
                return sanitize_cookies({str(k): str(v) for k, v in raw.items()})
    except Exception:
        pass
    return sanitize_cookies(parse_cookie_header(cookie_header))


def _build_config(
    url: str,
    cookies: Dict[str, str],
    output_root: Path,
    app_root: Path,
) -> ConfigLoader:
    config = ConfigLoader(None)
    config.update(
        link=[url],
        path=str(output_root),
        cookies=cookies,
        thread=_ANDROID_THREAD_COUNT,
        rate_limit=_ANDROID_RATE_LIMIT,
        database=True,
        database_path=str(app_root / "dy_downloader.db"),
        music=False,
        cover=False,
        avatar=False,
        json=False,
        folderstyle=True,
        auto_cookie=False,
        browser_fallback={"enabled": False},
        transcript={"enabled": False},
        comments={"enabled": False},
        notifications={"enabled": False, "providers": []},
    )
    return config


async def _resolve_input_url(input_text: str, api_client: DouyinAPIClient) -> str:
    explicit_url = _extract_first_url(input_text) or input_text.strip()
    if is_short_url(explicit_url):
        resolved = await api_client.resolve_short_url(normalize_short_url(explicit_url))
        if resolved:
            return resolved
    return explicit_url


def _extract_first_url(text: str) -> Optional[str]:
    match = re.search(r"https?://[^\s\"'<>]+", text or "")
    if not match:
        return None
    return match.group(0).rstrip(".,;，。；)")


def _changed_files_since(root: Path, started_at: float) -> List[str]:
    changed: List[str] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix == ".tmp":
            continue
        try:
            if path.stat().st_mtime >= started_at:
                changed.append(str(path))
        except OSError:
            pass
    return sorted(changed)


def _summary_message(result: Any, files: List[str]) -> str:
    if result is None:
        return "下载失败"
    if getattr(result, "success", 0) > 0:
        return f"下载完成，新增 {len(files)} 个文件"
    if getattr(result, "skipped", 0) > 0:
        return "作品已存在，已跳过"
    return "下载失败，请检查 Cookie 或链接权限"


def _mark_timing(timings: Dict[str, int], name: str, started_perf: float) -> None:
    timings[name] = int((time.perf_counter() - started_perf) * 1000)


def _error(message: str, output_root: Path, timings: Optional[Dict[str, int]] = None) -> Dict[str, Any]:
    return {
        "ok": False,
        "error": message,
        "message": message,
        "total": 0,
        "success": 0,
        "failed": 1,
        "skipped": 0,
        "output_dir": str(output_root),
        "files": [],
        "timings": timings or {},
    }
