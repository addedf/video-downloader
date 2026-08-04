<p align="center">
  <img src="docs/images/app-icon.svg" width="112" alt="Douyin Downloader icon">
</p>

<h1 align="center">Douyin Downloader</h1>

<p align="center">
  An Android workflow that turns Douyin share text into media you can confirm, preview, save, and find again.
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="https://github.com/addedf/video-downloader/releases/tag/v2.3.3">Download v2.3.3</a>
</p>

Douyin Downloader is an open-source Android app for parsing Douyin share text, short links, and work links, then saving available videos or image posts to the system media library. The interface is native Android; Chaquopy bridges the UI with Python-based parsing and download logic.

## The v2 design

Version 2 treats downloading as one connected utility flow instead of a single action:

- **Confirm before parsing.** When a usable link is found in the clipboard, the app asks before filling and parsing it. Users can also paste, edit, or clear the input themselves.
- **Preview before saving.** Parsed content is centered on a media preview. Image and video tabs, plus thumbnails, make it easy to verify the target before saving it.
- **Keep progress visible.** A floating progress indicator makes the running save task easy to track.
- **Keep completed work useful.** The account area retains recent downloads and provides open, share, retry, and history-removal actions.
- **Stay compact and direct.** A restrained black, white, and cyan interface keeps attention on the link, the media, and the next action rather than turning a utility into a marketing page.

## Flow

1. Copy a Douyin share text or work link.
2. Confirm the clipboard prompt, or paste the text and select **Parse resources**.
3. Switch between images and video, browse thumbnails, and verify the result.
4. Select **Save to album** and follow the progress state.
5. Use **My - Recent downloads** to open, share, retry, or remove a record.

## UI preview

The screenshots below are rendered from the repository's [v2 UI reference prototype](docs/prototypes/app-ui-prototype.html). They document the current interaction states and visual direction.

| Clipboard link detected | Parsed media preview |
| --- | --- |
| <img src="docs/images/v2-clipboard-detection.png" width="300" alt="Clipboard detection and parse confirmation"> | <img src="docs/images/v2-resource-preview.png" width="300" alt="Image post and video preview"> |

| Save progress | Login state and recent downloads |
| --- | --- |
| <img src="docs/images/v2-download-progress.png" width="300" alt="Save progress"> | <img src="docs/images/v2-account-history.png" width="300" alt="Account area with recent downloads"> |

## Features

- Parse Douyin share text, short links, and work links
- Preview and save available videos, image posts, covers, and other media
- Receive text links from the Android share sheet
- Sign in through an embedded WebView; cookies are encrypted and kept locally
- Show download progress, transfer speed, and result summaries
- Write saved media to the system gallery/media library and manage the latest record
- Check for app updates at startup

Availability depends on a work's public status, platform rules, and login state.

## Cookies and privacy

Some content may require a logged-in session. Cookies collected through the built-in WebView are encrypted and stored only on the device for the corresponding Douyin requests; they are not uploaded to a third-party service.

Never post values such as `sessionid` in issues, screenshots, or logs.

## Development

- Android Studio
- Android 9.0+ (minSdk 28)
- JDK 11
- Kotlin, AndroidX, Material Components, Kotlin Coroutines
- Chaquopy with Python 3.12
- Moshi

## Project layout

```text
app/src/main/java/com/zemin/downloader
+-- common/          # Shared Android/Python interfaces, storage, and utilities
+-- impl/dy/         # Douyin bridge, login, and downloader implementations
+-- ui/              # Main interface, login, and UI utilities
+-- update/          # App update checks

app/src/main/python
+-- dy/              # Douyin parsing, download, and file handling
+-- common/          # Python-to-Android support utilities
```

## Notes

- This project is intended for learning, research, and personal data backup.
- Follow the target platform's terms, copyright rules, and applicable law.
- Do not use it for bulk scraping, commercial distribution, infringement, or other improper purposes.
- Third-party platform APIs, risk controls, and page structures can change at any time, so ongoing maintenance may be required.

## References

- [JoeanAmier/XHS-Downloader](https://github.com/JoeanAmier/XHS-Downloader)
- [jiji262/douyin-downloader](https://github.com/jiji262/douyin-downloader)
