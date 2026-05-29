from __future__ import annotations

from asyncio import Event, Queue
from datetime import datetime
from pathlib import Path
from re import compile
from types import SimpleNamespace
from urllib.parse import urlparse

from .source_bootstrap import install_module_exports

install_module_exports()

from .android_xhs_util import AndroidConverter, Print
from source.application.download import Download
from source.application.explore import Explore
from source.application.image import Image
from source.application.request import Html
from source.application.video import Video
from source.expansion import Cleaner, Namespace, beautify_string
from source.module import (
    DataRecorder,
    ERROR,
    IDRecorder,
    INFO,
    Manager,
    MapRecorder,
    Mapping,
    ROOT,
    WARNING,
    logging,
)
from source.translation import _, switch_language

__all__ = ["AndroidXHS"]


def data_cache(function):
    async def inner(self, data: dict):
        if self.manager.record_data:
            download = data["下载地址"]
            lives = data["动图地址"]
            await function(self, data)
            data["下载地址"] = download
            data["动图地址"] = lives

    return inner


class AndroidXHS:
    LINK = compile(r"(?:https?://)?www\.xiaohongshu\.com/explore/\S+")
    USER = compile(r"(?:https?://)?www\.xiaohongshu\.com/user/profile/[a-z0-9]+/\S+")
    SHARE = compile(r"(?:https?://)?www\.xiaohongshu\.com/discovery/item/\S+")
    SHORT = compile(r"(?:https?://)?xhslink\.com/[^\s\"<>\\^`{|}，。；！？、【】《》]+")
    ID = compile(r"(?:explore|item)/(\S+)?\?")
    ID_USER = compile(r"user/profile/[a-z0-9]+/(\S+)?\?")
    CLEANER = Cleaner()

    def __init__(
            self,
            mapping_data: dict | None = None,
            work_path: str = "",
            folder_name: str = "XHS",
            name_format: str = "发布时间 作者昵称 作品标题",
            user_agent: str | None = None,
            cookie: str = "",
            proxy: str | dict | None = None,
            timeout: int = 10,
            chunk: int = 1024 * 1024,
            max_retry: int = 3,
            record_data: bool = False,
            image_format: str = "JPEG",
            image_download: bool = True,
            video_download: bool = True,
            live_download: bool = False,
            video_preference: str = "resolution",
            folder_mode: bool = False,
            download_record: bool = True,
            author_archive: bool = False,
            write_mtime: bool = False,
            language: str = "zh_CN",
            root: Path | None = None,
    ):
        switch_language(language)
        self.print = Print()
        self.manager = Manager(
            root or ROOT,
            work_path,
            folder_name,
            name_format,
            chunk,
            user_agent,
            cookie,
            proxy,
            timeout,
            max_retry,
            record_data,
            image_format,
            image_download,
            video_download,
            live_download,
            video_preference,
            download_record,
            folder_mode,
            author_archive,
            write_mtime,
            False,
            self.CLEANER,
            self.print,
        )
        self.mapping_data = mapping_data or {}
        self.map_recorder = MapRecorder(self.manager)
        self.mapping = Mapping(self.manager, self.map_recorder)
        self.html = Html(self.manager)
        self.image = Image()
        self.video = Video()
        self.explore = Explore()
        self.convert = AndroidConverter()
        self.download = Download(self.manager)
        self.id_recorder = IDRecorder(self.manager)
        self.data_recorder = DataRecorder(self.manager)
        self.queue = Queue()
        self.event = Event()

    def __extract_image(self, container: dict, data: Namespace):
        container["下载地址"], container["动图地址"] = self.image.get_image_link(
            data, self.manager.image_format
        )

    def __extract_video(self, container: dict, data: Namespace):
        container["下载地址"] = self.video.deal_video_link(
            data,
            self.manager.video_preference,
        )
        container["动图地址"] = [None]

    async def __download_files(
            self,
            container: dict,
            download: bool,
            index,
            count: SimpleNamespace,
    ):
        name = self.__naming_rules(container)
        if (urls := container["下载地址"]) and download:
            if await self.skip_download(i := container["作品ID"]):
                self.logging(_("作品 {0} 存在下载记录，跳过下载").format(i))
                count.skip += 1
            else:
                _, result = await self.download.run(
                    urls,
                    container["动图地址"],
                    index,
                    container["作者ID"] + "_" + self.CLEANER.filter_name(container["作者昵称"]),
                    name,
                    container["作品类型"],
                    container["时间戳"],
                )
                if not result:
                    count.skip += 1
                elif all(result):
                    count.success += 1
                    await self.__add_record(i)
                else:
                    count.fail += 1
        elif not urls:
            self.logging(_("提取作品文件下载地址失败"), ERROR)
            count.fail += 1
        await self.save_data(container)

    @data_cache
    async def save_data(self, data: dict):
        data["采集时间"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        data["下载地址"] = " ".join(data["下载地址"])
        data["动图地址"] = " ".join(i or "NaN" for i in data["动图地址"])
        data.pop("时间戳", None)
        await self.data_recorder.add(**data)

    async def __add_record(self, id_: str) -> None:
        await self.id_recorder.add(id_)

    async def extract(
            self,
            url: str,
            download: bool = False,
            index: list | tuple | None = None,
            data: bool = True,
    ) -> list[dict]:
        if not (urls := await self.extract_links(url)):
            self.logging(_("提取小红书作品链接失败"), WARNING)
            return []
        statistics = SimpleNamespace(all=len(urls), success=0, fail=0, skip=0)
        return [
            await self.__deal_extract(i, download, index, data, count=statistics)
            for i in urls
        ]

    async def extract_links(self, url: str) -> list[str]:
        urls = []
        for item in str(url or "").split():
            if short := self.SHORT.search(item):
                item = await self.html.request_url(short.group(), False)
            if share := self.SHARE.search(item):
                urls.append(share.group())
            elif link := self.LINK.search(item):
                urls.append(link.group())
            elif user := self.USER.search(item):
                urls.append(user.group())
        return urls

    async def _get_html_data(
            self,
            url: str,
            data: bool,
            cookie: str | None = None,
            proxy: str | None = None,
            count=SimpleNamespace(all=0, success=0, fail=0, skip=0),
    ) -> tuple[str, Namespace | dict]:
        if await self.skip_download(id_ := self.__extract_link_id(url)) and not data:
            count.skip += 1
            return id_, {"message": _("作品 {0} 存在下载记录，跳过处理").format(id_)}
        html = await self.html.request_url(url, cookie=cookie, proxy=proxy)
        namespace = self.__generate_data_object(html)
        if not namespace:
            self.logging(_("{0} 获取数据失败").format(id_), ERROR)
            count.fail += 1
            return id_, {}
        return id_, namespace

    def _extract_data(self, namespace: Namespace, id_: str, count):
        data = self.explore.run(namespace)
        if not data:
            self.logging(_("{0} 提取数据失败").format(id_), ERROR)
            count.fail += 1
            return {}
        return data

    async def _deal_download_tasks(
            self,
            data: dict,
            namespace: Namespace,
            id_: str,
            download: bool,
            index: list | tuple | None,
            count: SimpleNamespace,
    ):
        if data["作品类型"] == _("视频"):
            self.__extract_video(data, namespace)
        elif data["作品类型"] in {_("图文"), _("图集")}:
            self.__extract_image(data, namespace)
        else:
            self.logging(_("未知的作品类型：{0}").format(id_), WARNING)
            data["下载地址"] = []
            data["动图地址"] = []
        await self.update_author_nickname(data)
        await self.__download_files(data, download, index, count)
        return data

    async def __deal_extract(
            self,
            url: str,
            download: bool,
            index: list | tuple | None,
            data: bool,
            cookie: str | None = None,
            proxy: str | None = None,
            count=SimpleNamespace(all=0, success=0, fail=0, skip=0),
    ):
        id_, namespace = await self._get_html_data(url, data, cookie, proxy, count)
        if not isinstance(namespace, Namespace):
            return namespace
        if not (data := self._extract_data(namespace, id_, count)):
            return data
        data = await self._deal_download_tasks(
            data | {"作品链接": url},
            namespace,
            id_,
            download,
            index,
            count,
        )
        self.logging(_("作品处理完成：{0}").format(id_))
        return data

    async def update_author_nickname(self, container: dict):
        if a := self.CLEANER.filter_name(self.mapping_data.get(i := container["作者ID"], "")):
            container["作者昵称"] = a
        else:
            container["作者昵称"] = self.manager.filter_name(container["作者昵称"]) or i
        await self.mapping.update_cache(i, container["作者昵称"])

    @staticmethod
    def __extract_link_id(url: str) -> str:
        link = urlparse(url)
        return link.path.split("/")[-1]

    def __generate_data_object(self, html: str) -> Namespace:
        return Namespace(self.convert.run(html))

    def __naming_rules(self, data: dict) -> str:
        keys = self.manager.name_format.split()
        values = []
        for key in keys:
            match key:
                case "发布时间":
                    values.append(self.__get_name_time(data))
                case "作品标题":
                    values.append(self.__get_name_title(data))
                case _:
                    values.append(data[key])
        return beautify_string(
            self.CLEANER.filter_name(
                self.manager.SEPARATE.join(values),
                default=self.manager.SEPARATE.join((data["作者ID"], data["作品ID"])),
            ),
            length=128,
        )

    @staticmethod
    def __get_name_time(data: dict) -> str:
        return data["发布时间"].replace(":", ".")

    def __get_name_title(self, data: dict) -> str:
        return beautify_string(
            self.manager.filter_name(data["作品标题"]),
            64,
        ) or data["作品ID"]

    async def skip_download(self, id_: str) -> bool:
        return bool(await self.id_recorder.select(id_))

    async def __aenter__(self):
        await self.id_recorder.__aenter__()
        await self.data_recorder.__aenter__()
        await self.map_recorder.__aenter__()
        return self

    async def __aexit__(self, exc_type, exc_value, traceback):
        await self.id_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.data_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.map_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.close()

    async def close(self):
        await self.manager.close()

    def logging(self, text, style=INFO):
        logging(self.print, text, style)
