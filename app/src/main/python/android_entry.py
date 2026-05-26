import asyncio
import json
import os
import re
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional
from cli import AndroidProgressReporter, AndroidGlobalConfig
from auth import CookieManager
from config import ConfigLoader
from control import QueueManager, RateLimiter, RetryHandler
from core import DouyinAPIClient, DownloaderFactory, URLParser
from storage import Database, FileManager
from utils.cookie_utils import parse_cookie_header, sanitize_cookies
from utils.validators import is_short_url, normalize_short_url

os.environ.setdefault("PYTHONUTF8", "1")

_android_global_config = AndroidGlobalConfig()

_ANDROID_USE_DATABASE = False
_ANDROID_THREAD_COUNT = 4
_ANDROID_RATE_LIMIT = 4.0
_ANDROID_RETRY_TIMES = 2


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    try:
        _prepare_runtime(Path(app_data_dir))
        asyncio.run(_init_config(app_data_dir, output_dir, cookie_header))
        return json.dumps({"ok": True}, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


def _prepare_runtime(app_root: Path) -> None:
    os.environ.setdefault("HOME", str(app_root))
    os.environ.setdefault("TMPDIR", str(app_root / "tmp"))
    os.environ.setdefault("PYTHONUTF8", "1")
    Path(os.environ["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))


async def _init_config(app_data_dir: str, output_dir: str, cookie_header: str):
    app_root = Path(app_data_dir)
    output_root = Path(output_dir)
    app_root.mkdir(parents=True, exist_ok=True)
    output_root.mkdir(parents=True, exist_ok=True)
    cookies = _parse_cookies(cookie_header)

    config = _build_config(cookies, output_root, app_root)
    cookie_manager = CookieManager(str(app_root / ".cookies.json"))
    cookie_manager.set_cookies(cookies)

    _android_global_config.config_loader = config
    _android_global_config.cookie_manager = cookie_manager
    _android_global_config.database = None

    if config.get("database"):
        database = Database(db_path=str(app_root / "dy_downloader.db"))
        _android_global_config.database = database
        await database.initialize()


def _refresh_cookies(cookie_header: str):
    if _android_global_config.cookie_manager is None:
        raise RuntimeError("Python runtime is not initialized")
    cookie_manager = _android_global_config.cookie_manager
    cookie_manager.set_cookies(_parse_cookies(cookie_header))


def download(input_text: str, cookie_header: str) -> str:
    try:
        _refresh_cookies(cookie_header)
        result = asyncio.run(_download_async(input_text))
    except Exception as exc:
        result = {
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(limit=12),
            "files": [],
        }
    return json.dumps(result, ensure_ascii=False)


async def _download_async(input_text: str) -> Dict[str, Any]:
    started_wall = time.time()

    cookie_manager = _android_global_config.cookie_manager
    if cookie_manager is None or _android_global_config.config_loader is None:
        return _error("Python runtime is not initialized")
    cookies = cookie_manager.get_cookies()
    config = _android_global_config.config_loader
    database = _android_global_config.database

    output_root = Path(config.get("path"))

    if not cookies:
        return _error("请先登录抖音获取 Cookie")

    url = str(input_text or "").strip()
    if not url:
        return _error("请先粘贴抖音分享文本或链接")

    reporter = AndroidProgressReporter()
    try:
        async with DouyinAPIClient(cookies, proxy=config.get("proxy")) as api_client:
            resolved_url = await _resolve_input_url(url, api_client)
            parsed = URLParser.parse(resolved_url)
            if not parsed:
                return _error(f"无法识别链接类型: {url}", output_root)

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
                return _error(f"暂不支持的链接类型: {parsed.get('type')}", output_root)

            result = await downloader.download(parsed)
            files = _changed_files_since(output_root, started_wall)
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
                "message": _summary_message(result, files)
            }
    finally:
        if database is not None:
            await database.close()


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


def _build_config(cookies: Dict[str, str], output_root: Path, app_root: Path) -> ConfigLoader:
    config = ConfigLoader(None)
    config.update(
        path=str(output_root),
        cookies=cookies,
        thread=_ANDROID_THREAD_COUNT,
        rate_limit=_ANDROID_RATE_LIMIT,
        retry_times=_ANDROID_RETRY_TIMES,
        database=_ANDROID_USE_DATABASE,
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


def _error(
    message: str,
    output_root: Optional[Path] = None,
    timings: Optional[Dict[str, int]] = None,
) -> Dict[str, Any]:
    return {
        "ok": False,
        "error": message,
        "message": message,
        "total": 0,
        "success": 0,
        "failed": 1,
        "skipped": 0,
        "output_dir": str(output_root) if output_root is not None else "",
        "files": [],
        "timings": timings or {},
        "download_metrics": [],
        "api_metrics": [],
    }
