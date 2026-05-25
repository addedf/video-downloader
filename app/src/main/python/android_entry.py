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
_DOWNLOAD_METRICS: List[Dict[str, Any]] = []
_API_METRICS: List[Dict[str, Any]] = []
_ANDROID_DOWNLOAD_CHUNK_SIZE = 1024 * 1024
_ANDROID_THREAD_COUNT = 4
_ANDROID_RATE_LIMIT = 4.0
_ANDROID_RETRY_TIMES = 2
_ANDROID_TOTAL_TIMEOUT_SECONDS = 90
_ANDROID_CONNECT_TIMEOUT_SECONDS = 10
_ANDROID_IDLE_READ_TIMEOUT_SECONDS = 25
_ANDROID_DETAIL_AID_CANDIDATES = ("1128", "6383")
_ANDROID_DETAIL_RETRIES = 1
_ANDROID_DETAIL_TIMEOUT_SECONDS = 6.0
_ANDROID_DETAIL_CONNECT_TIMEOUT_SECONDS = 3.0
_ANDROID_DETAIL_READ_TIMEOUT_SECONDS = 5.0
_ANDROID_MS_TOKEN_CONF_TIMEOUT_SECONDS = 2.0
_ANDROID_MS_TOKEN_REQUEST_TIMEOUT_SECONDS = 2.5


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
    _DOWNLOAD_METRICS.clear()
    _API_METRICS.clear()

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
            _patch_api_client_for_android(api_client)
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
                "download_metrics": list(_DOWNLOAD_METRICS),
                "api_metrics": list(_API_METRICS),
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
        started_at = time.perf_counter()
        first_chunk_ms: Optional[int] = None
        written = 0
        try:
            async with session.get(
                url,
                timeout=aiohttp.ClientTimeout(
                    total=_ANDROID_TOTAL_TIMEOUT_SECONDS,
                    sock_connect=_ANDROID_CONNECT_TIMEOUT_SECONDS,
                    sock_read=_ANDROID_IDLE_READ_TIMEOUT_SECONDS,
                ),
                headers=headers,
                proxy=proxy or None,
            ) as response:
                if response.status != 200:
                    _record_download_metric(
                        save_path,
                        started_at,
                        status=response.status,
                        bytes_written=0,
                        expected_size=response.content_length,
                        first_chunk_ms=first_chunk_ms,
                        ok=False,
                    )
                    return False

                final_path = self._resolve_save_path_from_content_type(
                    save_path,
                    response.headers,
                    prefer_response_content_type=prefer_response_content_type,
                )
                tmp_path = final_path.with_suffix(final_path.suffix + ".tmp")
                expected_size = response.content_length
                async with aiofiles.open(tmp_path, "wb") as file:
                    async for chunk in response.content.iter_chunked(_ANDROID_DOWNLOAD_CHUNK_SIZE):
                        if first_chunk_ms is None:
                            first_chunk_ms = int((time.perf_counter() - started_at) * 1000)
                        await file.write(chunk)
                        written += len(chunk)

                if expected_size is not None and written != expected_size:
                    tmp_path.unlink(missing_ok=True)
                    _record_download_metric(
                        final_path,
                        started_at,
                        status=response.status,
                        bytes_written=written,
                        expected_size=expected_size,
                        first_chunk_ms=first_chunk_ms,
                        ok=False,
                    )
                    return False

                os.replace(str(tmp_path), str(final_path))
                _record_download_metric(
                    final_path,
                    started_at,
                    status=response.status,
                    bytes_written=written,
                    expected_size=expected_size,
                    first_chunk_ms=first_chunk_ms,
                    ok=True,
                )
                return final_path if return_saved_path else True
        except Exception as exc:
            tmp_path.unlink(missing_ok=True)
            _record_download_metric(
                final_path,
                started_at,
                bytes_written=written,
                first_chunk_ms=first_chunk_ms,
                ok=False,
                error=type(exc).__name__,
            )
            return False
        finally:
            if should_close:
                await session.close()

    FileManager.download_file = download_file
    _FILE_MANAGER_PATCHED = True


def _patch_api_client_for_android(api_client: DouyinAPIClient) -> None:
    if getattr(api_client, "_android_metrics_patched", False):
        return

    async def _ensure_ms_token_fast() -> str:
        token = (getattr(api_client, "_ms_token", "") or "").strip()
        if token:
            setattr(api_client, "_android_ms_token_source", "cached")
            return token

        token = (api_client.cookies.get("msToken") or "").strip()
        if not token:
            original_conf_timeout = getattr(api_client._ms_token_manager, "conf_timeout_seconds", None)
            original_token_timeout = getattr(
                api_client._ms_token_manager, "token_timeout_seconds", None
            )
            api_client._ms_token_manager.conf_timeout_seconds = (
                _ANDROID_MS_TOKEN_CONF_TIMEOUT_SECONDS
            )
            api_client._ms_token_manager.token_timeout_seconds = (
                _ANDROID_MS_TOKEN_REQUEST_TIMEOUT_SECONDS
            )
            try:
                token = await asyncio.to_thread(api_client._ms_token_manager.gen_real_ms_token)
            finally:
                if original_conf_timeout is not None:
                    api_client._ms_token_manager.conf_timeout_seconds = original_conf_timeout
                if original_token_timeout is not None:
                    api_client._ms_token_manager.token_timeout_seconds = original_token_timeout

            if token:
                setattr(api_client, "_android_ms_token_source", "real")
            else:
                token = api_client._ms_token_manager.gen_false_ms_token()
                setattr(api_client, "_android_ms_token_source", "fallback")
        else:
            setattr(api_client, "_android_ms_token_source", "cookie")

        api_client._ms_token = token
        api_client.cookies["msToken"] = token
        if api_client._session and not api_client._session.closed:
            api_client._session.cookie_jar.update_cookies({"msToken": token})
        return token

    async def _request_detail_json(
        params: Dict[str, Any],
        attempt: Dict[str, Any],
        *,
        suppress_error: bool,
    ) -> Dict[str, Any]:
        path = "/aweme/v1/web/aweme/detail/"
        sign_started_at = time.perf_counter()
        signed_url, ua = api_client.build_signed_path(path, params)
        attempt["sign_ms"] = max(1, int((time.perf_counter() - sign_started_at) * 1000))

        http_started_at = time.perf_counter()
        try:
            session = await api_client.get_session()
            async with session.get(
                signed_url,
                headers={**api_client.headers, "User-Agent": ua},
                proxy=api_client.proxy or None,
                timeout=aiohttp.ClientTimeout(
                    total=_ANDROID_DETAIL_TIMEOUT_SECONDS,
                    sock_connect=_ANDROID_DETAIL_CONNECT_TIMEOUT_SECONDS,
                    sock_read=_ANDROID_DETAIL_READ_TIMEOUT_SECONDS,
                ),
            ) as response:
                attempt["status"] = int(response.status)
                body = await response.read()
                if response.status != 200:
                    attempt["error"] = f"HTTP {response.status}"
                    return {}
                if not body:
                    attempt["error"] = "EmptyBody"
                    return {}
                try:
                    data = await response.json(content_type=None)
                except Exception:
                    try:
                        data = json.loads(body)
                    except Exception:
                        attempt["error"] = "NonJsonBody"
                        return {}
                return data if isinstance(data, dict) else {}
        except asyncio.TimeoutError:
            attempt["error"] = "TimeoutError"
            return {}
        except Exception as exc:
            attempt["error"] = type(exc).__name__
            return {}
        finally:
            attempt["http_ms"] = max(1, int((time.perf_counter() - http_started_at) * 1000))

    api_client._ensure_ms_token = _ensure_ms_token_fast

    async def get_video_detail(aweme_id: str, *, suppress_error: bool = False):
        started_at = time.perf_counter()
        ok = False
        attempts: List[Dict[str, Any]] = []
        try:
            for aid in _ANDROID_DETAIL_AID_CANDIDATES:
                attempt_started_at = time.perf_counter()
                attempt: Dict[str, Any] = {"aid": aid, "ok": False}
                try:
                    token_started_at = time.perf_counter()
                    params = await api_client._default_query()
                    attempt["token_ms"] = max(
                        1, int((time.perf_counter() - token_started_at) * 1000)
                    )
                    attempt["token_source"] = getattr(
                        api_client, "_android_ms_token_source", "unknown"
                    )
                    params.update(
                        {
                            "aweme_id": aweme_id,
                            "aid": aid,
                        }
                    )
                    data = await asyncio.wait_for(
                        _request_detail_json(
                            params,
                            suppress_error=(
                                suppress_error or aid != _ANDROID_DETAIL_AID_CANDIDATES[-1]
                            ),
                            attempt=attempt,
                        ),
                        timeout=_ANDROID_DETAIL_TIMEOUT_SECONDS,
                    )
                except asyncio.TimeoutError:
                    attempt["error"] = "TimeoutError"
                    attempts.append(_finish_api_attempt(attempt, attempt_started_at))
                    continue
                except Exception as exc:
                    attempt["error"] = type(exc).__name__
                    attempts.append(_finish_api_attempt(attempt, attempt_started_at))
                    continue

                detail = data.get("aweme_detail") if isinstance(data, dict) else None
                attempt["ok"] = bool(detail)
                filter_info = data.get("filter_detail") if isinstance(data, dict) else None
                if isinstance(filter_info, dict) and filter_info.get("filter_reason"):
                    attempt["filter_reason"] = str(filter_info["filter_reason"])
                attempts.append(_finish_api_attempt(attempt, attempt_started_at))
                if detail:
                    ok = True
                    return detail

            return None
        finally:
            _API_METRICS.append(
                {
                    "name": "get_video_detail",
                    "ok": ok,
                    "duration_ms": max(1, int((time.perf_counter() - started_at) * 1000)),
                    "attempts": attempts,
                }
            )

    api_client.get_video_detail = get_video_detail
    setattr(api_client, "_android_metrics_patched", True)


def _finish_api_attempt(attempt: Dict[str, Any], started_at: float) -> Dict[str, Any]:
    attempt["duration_ms"] = max(1, int((time.perf_counter() - started_at) * 1000))
    return attempt


def _record_download_metric(
    path: Path,
    started_at: float,
    *,
    bytes_written: int,
    ok: bool,
    status: Optional[int] = None,
    expected_size: Optional[int] = None,
    first_chunk_ms: Optional[int] = None,
    error: Optional[str] = None,
) -> None:
    duration_ms = max(1, int((time.perf_counter() - started_at) * 1000))
    metric = {
        "file_name": path.name,
        "ok": ok,
        "status": status,
        "bytes": int(bytes_written or 0),
        "expected_bytes": int(expected_size or 0),
        "duration_ms": duration_ms,
        "first_chunk_ms": int(first_chunk_ms or 0),
        "speed_kbps": int((bytes_written or 0) / max(duration_ms / 1000, 0.001) / 1024),
    }
    if error:
        metric["error"] = error
    _DOWNLOAD_METRICS.append(metric)


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
        retry_times=_ANDROID_RETRY_TIMES,
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
        "download_metrics": [],
        "api_metrics": [],
    }
