# Outline Notes for Android

[![Android CI](https://github.com/uvian/outline-android/actions/workflows/ci.yml/badge.svg)](https://github.com/uvian/outline-android/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一个专为自托管 [Outline](https://github.com/outline/outline) 打造的**离线优先（Offline-First）安卓原生笔记客户端**。

> An unofficial, open-source, offline-first native Android client for self-hosted **Outline** wikis — built with Kotlin, Jetpack Compose, Room (FTS4), and an embedded offline Tiptap (ProseMirror) rich-text & Markdown editor.
>
> *Disclaimer: This is an independent community project and is not officially affiliated with General Outline, Inc.*

---

## 核心特性 / Features

- **Outline 风格现代界面与品牌同步**
  - 原生 Kotlin + Jetpack Compose (Material 3)，自动同步云端工作区 Logo 与自定义强调色（也可在设置中切换预设或自定义 `#HEX` 色值），支持浅色 / 深色 / 跟随系统主题。
  - 全局热数据仓库 + 预热复用离线编辑器，进出笔记零闪白、丝滑跟手。
- **真正离线可用 & 双向安全同步**
  - Room (SQLite) + **FTS4 全文检索**本地镜像集合、多级嵌套文档树、收藏夹、草稿箱、归档与回收站。
  - 无网状态下可直接打开阅读、新建、编辑笔记与离线插图；恢复网络后通过 `WorkManager` 自动按序上传，支持每 30 分钟后台静默同步。
  - **版本冲突保护**：覆盖上传前自动比对云端 `revision` / `updatedAt`，冲突时弹窗提供「保留两边（本地另存副本）」「保留本地」「采用云端」，防止静默丢数据。
- **富文本 + Markdown 双模式编辑器**
  - 内置离线单文件 **Tiptap (ProseMirror)** 富文本引擎（零外链），支持底部原生排版工具栏、Markdown 快捷键（`# `、`- `、`- [ ] `）、Outline 提示块（`:::info / :::tip / :::warning`）、表格上下文加/删行列浮条。
  - 支持**防误触阅读模式**（浏览长笔记不误弹键盘，仍可直接勾选待办清单；双击或点「编辑」即刻进入编辑），右上角一键切换 **「富文本 ↔ MD 源码」**。
  - 支持**站内 `/doc/xxx` 双链 App 内直跳**、**长文档大纲目录（TOC）**、以及**全屏图片双指缩放与保存到相册（Lightbox）**。
- **MinIO / S3 附件直传优化**
  - 适配 Outline 两步上传（`attachments.create` → S3/MinIO 预签名表单直传，隔离 Bearer Token 避免验签冲突），支持相册选图或相机直拍、EXIF 方向校正与智能压缩（上限 2048px）。
  - 文档内私有图片自动鉴权拉取并本地磁盘缓存，离线可正常浏览。
- **个人效率与隐私安全**
  - **安卓桌面快捷小组件**：桌面一键离线速记 + 最近 3 篇笔记直达；支持从微信/浏览器通过系统「分享」剪藏文字、链接或图片。
  - **Android Keystore AES-256** 加密存储 API 密钥 + 可选生物识别/系统密码应用锁。
  - 支持单篇 Markdown 分享、全量笔记按集合目录导出为 `.zip` 本地备份，以及查看/导出同步诊断日志。

---

## 快速安装 / Installation

1. 前往本仓库右侧的 **[Releases](https://github.com/uvian/outline-android/releases)** 页面，下载最新的 `OutlineNotes-v*.apk`（经 R8 优化瘦身，仅约 2.7 MB）并安装到 Android 8.0+ 手机。
2. 打开电脑端 Outline 网页版 → **Settings（设置）→ API Keys（API 密钥）** → 新建一个个人密钥（以 `ol_api_` 开头）。
3. 打开手机端 **Outline Notes**，填入你的服务器地址（例如 `https://wiki.yourdomain.com`）与 API Key，点击「连接并同步笔记」即可开始使用。

### 自托管 MinIO / S3 传图提示
若 Outline 与 MinIO 部署在同一台服务器上，请确保 Outline 的 `AWS_S3_UPLOAD_BUCKET_URL` 配置的是手机公网可访问的 HTTPS 地址（而非 `127.0.0.1` 或 Docker 内网名）。只要电脑浏览器端能正常上传图片，手机端即可开箱使用。

---

## 项目结构 / Project Structure

```text
├── editor-web/                 # Vite + TypeScript + Tiptap (ProseMirror) 离线编辑器源码
├── app/
│   ├── src/main/assets/editor/ # 打包后的单文件离线编辑器 (index.html)
│   └── src/main/java/com/outline/notes/
│       ├── data/
│       │   ├── local/          # Room SQLite + FTS4 + WorkspaceStore 全局热流
│       │   ├── remote/         # Outline RPC API 客户端与 MinIO 预签名直传
│       │   ├── sync/           # 离线优先双向同步引擎、WorkManager 与诊断日志
│       │   ├── media/          # EXIF 校正、图片压缩与相册保存
│       │   ├── backup/         # 全量 Markdown ZIP 备份导出
│       │   ├── prefs/          # DataStore 偏好设置
│       │   └── security/       # Android Keystore AES-256-GCM 密钥加密
│       ├── ui/                 # Jetpack Compose (Material 3) 界面与预热 WebView 池
│       └── widget/             # 安卓桌面快捷速记小组件
└── .github/workflows/ci.yml    # GitHub Actions 自动测试与构建
```

---

## 从源码构建 / Build from Source

环境要求：JDK 21、Android SDK (API 35)、Node.js 20+。

```bash
# 1. 构建离线富文本编辑器（仅在修改 editor-web/ 后需要）
cd editor-web
npm ci
npm run build
cd ..

# 2. 运行单元测试并编译 Debug APK
./gradlew testDebugUnitTest assembleDebug

# 3. 编译 Release 包（如需正式签名，可放置 keystore/outline-release.jks 并在环境变量或 local.properties 中配置）
#    OUTLINE_STORE_PASSWORD / OUTLINE_KEY_ALIAS / OUTLINE_KEY_PASSWORD
./gradlew assembleRelease
```

---

## License

[MIT License](LICENSE)
