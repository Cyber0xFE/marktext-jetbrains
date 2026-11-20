# MarkText JetBrains Plugin

[中文说明](README.zh-CN.md)

This project is based on [MarkText](https://github.com/marktext/marktext), the open-source Markdown editor. It brings MarkText's Muya editing experience to IntelliJ-based IDEs as a standalone plugin project.

The project contains the plugin, its bundled Web host, and the Muya engine source used by that host.

## Acknowledgements

Thanks to the [MarkText project](https://github.com/marktext/marktext) and its contributors for creating and maintaining MarkText and the Muya editor engine that this plugin builds upon. This is an independent community plugin project and is not an official MarkText project.

## Requirements

- Node.js 22.12 or newer
- pnpm 10 or newer
- JDK 21

## Build

The build has two parts. Build the Web bundle first, then build the IntelliJ plugin.

### Command line

```powershell
pnpm install
pnpm run typecheck
pnpm run build:web
pnpm run build:plugin:fast
```

`pnpm run build:plugin` runs the same steps and uses the checked-in Gradle wrapper. `pnpm run build:plugin:fast` skips the IntelliJ searchable-options launch, which is useful when that sandbox cannot start in the local environment.

The plugin ZIP is written to `jetbrains-plugin/build/distributions/`.

### IntelliJ IDEA

To build with the Gradle distribution configured in IntelliJ IDEA, install the Node dependencies and build the Web bundle from the project root:

```powershell
pnpm install
pnpm run build:web
```

Then open `jetbrains-plugin` as a Gradle project in IDEA and run `buildPlugin` from the Gradle tool window. IDEA uses the Gradle distribution selected in its Gradle settings.

To launch the plugin in a development IDE from the command line, run `pnpm run runIde` after building the Web bundle.
