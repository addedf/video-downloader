import asyncio
import json
import os
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

from .android_xhs import AndroidXHS
from .android_flow_logger import AndroidFlowLogger, url_preview

_runtime: Dict[str, Any] = {
    "app_root": None,
    "output_root": None,
    "cookie": "",
}
_MEDIA_SUFFIXES = {
    ".gif",
    ".jpeg",
    ".jpg",
    ".m4a",
    ".mov",
    ".mp3",
    ".mp4",
    ".png",
    ".webp",
}


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    flow = AndroidFlowLogger()
    try:
        flow.info("warm_up.begin", app_data_dir=app_data_dir, output_dir=output_dir)
        with flow.stage("prepare_runtime"):
            _prepare_runtime(Path(app_data_dir), Path(output_dir))
        with flow.stage("init_config", has_cookie=bool(cookie_header)):
            _runtime["cookie"] = str(cookie_header or "")
        flow.mark_total()
        return json.dumps({"ok": True, "timings": dict(flow.timings)}, ensure_ascii=False)
    except Exception as exc:
        flow.mark_total()
        flow.error("warm_up.failed", error=str(exc))
        return json.dumps(
            {"ok": False, "error": str(exc), "timings": dict(flow.timings)},
            ensure_ascii=False,
        )


def refresh_cookies(cookie_header: str) -> str:
    _runtime["cookie"] = str(cookie_header or "")
    return json.dumps({"ok": True}, ensure_ascii=False)


def download(input_text: str) -> str:
    flow = AndroidFlowLogger()
    try:
        result = asyncio.run(_download_async(input_text, flow))
    except Exception as exc:
        flow.mark_total()
        flow.error("download.failed", error=str(exc), input=url_preview(input_text))
        result = _error(
            str(exc),
            timings=dict(flow.timings),
            traceback_text=traceback.format_exc(limit=12),
        )
    return json.dumps(result, ensure_ascii=False)


def _prepare_runtime(app_root: Path, output_root: Path) -> None:
    app_root = app_root / "xhs"
    output_root = output_root / "XHS"
    app_root.mkdir(parents=True, exist_ok=True)
    output_root.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HOME", str(app_root))
    os.environ.setdefault("TMPDIR", str(app_root / "tmp"))
    os.environ.setdefault("PYTHONUTF8", "1")
    Path(os.environ["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    package_root = Path(__file__).resolve().parents[1]
    if str(package_root) not in sys.path:
        sys.path.insert(0, str(package_root))
    _runtime["app_root"] = app_root
    _runtime["output_root"] = output_root


async def _download_async(input_text: str, flow: AndroidFlowLogger) -> Dict[str, Any]:
    if _runtime["app_root"] is None or _runtime["output_root"] is None:
        return _error("Python runtime is not initialized", timings=dict(flow.timings))

    input_text = str(input_text or "").strip()
    if not input_text:
        return _error("请先粘贴小红书分享文本或链接", timings=dict(flow.timings))

    app_root: Path = _runtime["app_root"]
    output_root: Path = _runtime["output_root"]
    cookie = str(_runtime.get("cookie") or "")
    started_wall = time.time()

    flow.info("download.begin", input=url_preview(input_text), output_dir=str(output_root))
    with flow.stage("download_content", has_cookie=bool(cookie)):
        async with AndroidXHS(
            root=app_root,
            work_path=str(output_root.parent),
            folder_name=output_root.name,
            cookie=cookie,
            timeout=15,
            max_retry=2,
            record_data=False,
            download_record=False,
            image_format="JPEG",
            live_download=False,
            author_archive=False,
            folder_mode=False,
            flow=flow,
        ) as xhs:
            items = await xhs.extract(input_text, download=True, data=True)

    with flow.stage("collect_files"):
        files, ignored_files = _changed_files_since(output_root, started_wall)
        flow.info("collect_files.result", files=len(files), ignored=len(ignored_files))

    ok_count = sum(1 for item in items if isinstance(item, dict) and item.get("下载地址"))
    failed = 0 if ok_count else 1
    success_count = len(files) or ok_count
    flow.mark_total()
    flow.info(
        "download.done",
        total_ms=flow.timings.get("total_ms"),
        items=len(items),
        success=success_count,
        failed=failed,
        files=len(files),
    )
    return {
        "ok": bool(files or ok_count),
        "message": _summary_message(files, ok_count),
        "error": "" if files or ok_count else "未下载到文件，请检查链接、Cookie 或作品权限",
        "output_dir": str(output_root),
        "files": files,
        "success": success_count,
        "failed": failed,
        "skipped": 0,
        "timings": dict(flow.timings),
        "download_metrics": [],
        "api_metrics": [],
        "items": items,
    }


def _changed_files_since(root: Path, started_at: float) -> tuple[List[str], List[str]]:
    changed: List[str] = []
    ignored: List[str] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        try:
            if path.stat().st_mtime >= started_at:
                if path.suffix.lower() in _MEDIA_SUFFIXES:
                    changed.append(str(path))
                else:
                    ignored.append(str(path))
        except OSError:
            pass
    return sorted(changed), sorted(ignored)


def _summary_message(files: List[str], ok_count: int) -> str:
    if files:
        return f"下载完成，新增 {len(files)} 个文件"
    if ok_count:
        return "作品解析完成，但没有发现新增文件"
    return "下载失败"


def _error(
    message: str,
    output_root: Optional[Path] = None,
    timings: Optional[Dict[str, int]] = None,
    traceback_text: str = "",
) -> Dict[str, Any]:
    return {
        "ok": False,
        "message": message,
        "error": message,
        "traceback": traceback_text,
        "output_dir": str(output_root) if output_root is not None else "",
        "files": [],
        "success": 0,
        "failed": 1,
        "skipped": 0,
        "timings": timings or {},
        "download_metrics": [],
        "api_metrics": [],
    }
