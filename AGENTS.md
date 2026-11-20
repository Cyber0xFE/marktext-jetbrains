# AGENTS.md

## 构建与安装约定

- 任何代码改动完成后，执行 `pnpm run build:plugin:fast`（构建 Web bundle → 打包插件 → 排队安装）。
  安装脚本会扫描本机所有 JetBrains IDE，通过每个 IDE 自带的 `StartupActionScriptManager`
  把「删除旧插件目录 + 解压新 ZIP」写入 `<system>/plugins/action.script`（与 IDE 的
  "Install Plugin from Disk" 同一机制），因此**正在运行的 IDE 也能排队**，重启 IDE 时由 IDE 自己完成替换。
  排队成功只代表已入队，重启对应 IDE 后才生效；不要为了安装而自动关闭或重启用户的 IDE。
- 无法排队时（缺少 bundled JBR / IDE 从未启动过）回退为直接解压到 `<config>/plugins/marktext-jetbrains`，这要求该 IDE 未运行。
- 只想校验类型/编译时用 `pnpm run typecheck`，只想重新排队已构建的 ZIP 时用 `pnpm run install:plugin`。
- 需要跳过自动安装：`MARKTEXT_SKIP_INSTALL=1 pnpm run build:plugin:fast`。
- 默认会跳过 `since-build` 不满足的旧 IDE；确有需要时用 `node scripts/install-plugin.mjs --force`。
- 排队会往 `<system>/plugins/` 写一份 ZIP 快照（`marktext-local-update-*.zip`），IDE 启动时解压后自动删除。
- 队列辅助工具 `jetbrains-plugin/tools/plugin-update/QueuePluginUpdateTool.java` 由 `install-plugin.mjs` 用 `javac --release 21` 编译到
  `jetbrains-plugin/build/queue-plugin-update-tool/`，再由目标 IDE 的 bundled JBR 执行（IDE 类可能需要比构建 JDK 更新的 JVM）。
- 排队失败时交付说明中必须明确报告失败的 IDE 与原因，不能把「仅 ZIP 打包成功」当作本地安装成功。
