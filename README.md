# SSH Deck

Android SSH client aimed at managing Ubuntu servers. Kotlin + Jetpack Compose, SSH via
[JSch (mwiede fork)](https://github.com/mwiede/jsch) with Bouncy Castle for ed25519/curve25519.

## Features

- **Terminal** – xterm-256color emulator (colors, cursor addressing, alt screen, line-drawing),
  so `top`, `htop`, `nano`, `less` work. Command-line mode with suggestions, or raw-keys mode for
  full-screen apps (switches automatically). Extra keys row: Ctrl, Esc, Tab, arrows, ^C, PgUp…
  Password prompts (`sudo`) are masked and never saved to history.
- **Files** – SFTP folder tree with expandable folders, breadcrumbs, hidden-file toggle,
  view/edit text files, upload/download, new folder, rename, delete, "cd here", "open in nano".
- **Commands** – recommendations based on the server's actual state (pending/security updates,
  reboot required, failed services, disk >80%, low memory, high load, big journal, docker/ufw),
  your most-used commands on that host (ranked by frequency + recency), and a searchable catalog
  of common Ubuntu admin commands. Commands with `<placeholders>` open in the input box to fill in.
- **Hosts** – password or private key (OpenSSH/PEM, paste or import). Secrets are encrypted with an
  Android Keystore AES-GCM key. Trust-on-first-use host key verification with a warning on change.

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # unsigned, minified
```

Requires JDK 17 and Android SDK 36 (`local.properties` → `sdk.dir`). minSdk 26.

## Code map

| Path | What |
|---|---|
| `ssh/SshConnection.kt` | JSch session, shell, exec, SFTP |
| `terminal/TerminalEmulator.kt` | VT100/xterm screen model |
| `terminal/TerminalRenderer.kt` | screen → Compose `AnnotatedString`s, key sequences |
| `SessionController.kt` | state for one live session (terminal, tree, insights) |
| `data/ServerInsights.kt` | probe script + recommendation rules |
| `data/CommandCatalog.kt`, `Suggestions.kt`, `CommandHistory.kt` | command suggestions |
| `ui/` | Compose screens |
