<p align="center">
  <img src="docs/images/app-icon.svg" width="112" alt="短视频下载器图标">
</p>

<h1 align="center">抖音下载器</h1>

<p align="center">
  面向 Android 端的抖音视频与图集下载工具。
</p>

项目通过 Android 原生界面承载解析、预览和保存流程，并使用 Chaquopy 集成 Python 下载逻辑。支持从抖音分享文本、短链或作品链接中解析内容，并将下载结果保存到系统媒体目录中。

## 界面预览

| 资源解析 | 下载中 | 下载完成 |
| --- | --- | --- |
| ![选择下载平台](docs/images/platform-selector.png) | ![下载中状态](docs/images/downloading.png) | ![下载完成信息](docs/images/download-complete.png) |

## 功能特性

- 支持粘贴抖音分享文本、短链或作品链接后解析
- 支持视频、图集与封面预览后保存
- 支持从系统分享菜单直接接收文本链接
- 支持通过内置 WebView 登录抖音，并加密保存 Cookie
- 支持下载进度、下载速度和结果摘要展示
- 支持查看最近下载、打开或分享已保存文件
- 支持视频、图片等媒体文件写入系统相册或媒体库
- 支持启动时检查应用更新

## 技术栈

- Android 原生应用
- Kotlin
- XML 布局 + ViewBinding
- AndroidX AppCompat / Material Components
- Kotlin Coroutines
- Chaquopy
- Python 3.12
- Moshi

## 应用架构

Android 侧负责 UI、登录、Cookie 加密存储、预览、下载历史和媒体注册；Python 侧负责抖音链接解析、接口请求、下载任务和结果回传。

## 项目结构

```text
app/src/main/java/com/zemin/downloader
+-- common/          # 公共接口、基础类、存储和工具能力
+-- impl/            # 平台能力实现
|   +-- dy/          # 抖音 Android 桥接模块
+-- ui/              # 主界面、登录页和 UI 工具
+-- update/          # 应用更新检查

app/src/main/python
+-- dy/              # 抖音 Python 下载核心
+-- common/          # Python 与 Android 交互的公共工具
```

## 使用方式

1. 在 Android Studio 中打开项目。
2. 根据本地环境配置 Android SDK、Gradle JDK 和 Chaquopy 所需的 Python 环境。
3. 安装应用到 Android 设备。
4. 打开应用后粘贴抖音分享文本或链接，点击解析资源。
5. 预览解析结果后，将所需视频或图片保存到系统相册。
6. 也可以在抖音中通过系统分享菜单将链接发送到本应用。

下载完成后，媒体文件会写入系统媒体库：

- 视频：`Movies/<平台名>`
- 图片：`Pictures/<平台名>`

## 登录说明

部分抖音内容可能依赖登录状态。应用内提供 WebView 登录页，登录完成后会加密保存 Cookie，并在后续解析与下载请求中复用登录信息。

## 注意事项

- 本项目仅用于学习、研究和个人数据备份场景。
- 请遵守目标平台的用户协议、版权规则和当地法律法规。
- 请勿将本项目用于批量抓取、商业分发、侵权传播或其他不当用途。
- 第三方平台接口、风控策略和页面结构可能随时变化，相关功能可能需要持续维护。

## 项目参考

- [JoeanAmier/XHS-Downloader](https://github.com/JoeanAmier/XHS-Downloader)
- [jiji262/douyin-downloader](https://github.com/jiji262/douyin-downloader)

## 仓库说明

国内 Gitee 镜像仓库：[VideoDownloader](https://gitee.com/maomao999/video-downloader)
