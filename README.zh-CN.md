# MarkText JetBrains 插件

[English README](README.md)

本项目基于开源 Markdown 编辑器 [MarkText](https://github.com/marktext/marktext) 开发，将 MarkText 的 Muya 编辑体验带到 JetBrains 系列 IDE 中，并作为独立插件项目维护。

项目包含 JetBrains 插件、内嵌的 Web 宿主，以及 Web 宿主使用的 Muya 编辑器引擎源码。

## 致谢

感谢 [MarkText 项目](https://github.com/marktext/marktext)及其贡献者创建并持续维护 MarkText 和 Muya 编辑器引擎，本插件的编辑体验建立在这些成果之上。本项目是独立的社区插件项目，并非 MarkText 官方项目。

## 环境要求

- Node.js 22.12 或更高版本
- pnpm 10 或更高版本
- JDK 21

## 构建

构建分为两个部分，先构建 Web bundle，再构建 IntelliJ 插件。

### 命令行构建

```powershell
pnpm install
pnpm run typecheck
pnpm run build:web
pnpm run build:plugin:fast
```

`pnpm run build:plugin` 会执行相同的构建流程，并使用项目自带的 Gradle wrapper。`pnpm run build:plugin:fast` 会跳过 IntelliJ searchable-options 沙箱启动，适合本机沙箱无法启动的情况。

构建完成后会自动调用 `scripts/install-plugin.mjs`，把插件排队到本机所有 JetBrains IDE。排队使用每个 IDE 自带的 `StartupActionScriptManager`（写入 `<system>/plugins/action.script`，与 IDE 的 "Install Plugin from Disk" 相同机制），因此正在运行的 IDE 也能排队，重启 IDE 时由 IDE 自己完成替换；IDE 重启后即生效。无法排队的 IDE 会回退为直接解压到 `<config>/plugins/marktext-jetbrains`（需该 IDE 未运行）。跳过自动安装可设置 `MARKTEXT_SKIP_INSTALL=1`，单独排队可运行 `pnpm run install:plugin`。

插件 ZIP 生成在 `jetbrains-plugin/build/distributions/`。

### 使用 IntelliJ IDEA 构建

如果使用 IntelliJ IDEA 中配置的 Gradle 发行版，先在项目根目录安装 Node 依赖并构建 Web bundle：

```powershell
pnpm install
pnpm run build:web
```

然后在 IDEA 中打开 `jetbrains-plugin` 目录作为 Gradle 项目，在 Gradle 工具窗口运行 `buildPlugin`。IDEA 会使用 Gradle 设置中选择的本地 Gradle 发行版。

构建 Web bundle 后，可通过 `pnpm run runIde` 从命令行启动用于开发的 IDE。
