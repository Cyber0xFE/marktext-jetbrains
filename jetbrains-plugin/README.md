# MarkText IntelliJ Plugin Prototype

This directory contains the initial IntelliJ Platform host for Muya.

## Prerequisites

- Java 21
- Gradle wrapper (9.8.1)
- Node.js and pnpm for the Muya Web bundle

## Build the Web bundle

Run this from the repository root.

```sh
pnpm install
pnpm --filter @marktext/jetbrains-web build
```

## Build the plugin

Run this from `jetbrains-plugin`.

```sh
./gradlew buildPlugin
```

On Windows, run `gradlew.bat buildPlugin`.

The Gradle build copies `packages/jetbrains-web/dist` into the plugin resources
before preparing the sandbox. The current prototype targets IntelliJ Platform
2025.3.1 and keeps the built-in Markdown editor visible as a fallback.

The editor bridge currently covers document loading, Muya-to-IntelliJ document
updates, IntelliJ-to-Muya reloads, and JCEF lifecycle cleanup. Image persistence,
native clipboard integration, selection restoration, and IntelliJ undo-stack
integration remain follow-up work.
