# Desktop app: build and develop

How to build the installers of the desktop app (`app-desktop/`) and work on it. Installing and
using the app: [`app-desktop/README.md`](../../app-desktop/README.md).

## What you need

Only for building: these are compilers and development headers, not needed to run the app.

| | |
|---|---|
| JDK | **25** (Temurin, at least 25.0.5.1); `JAVA_HOME` pointing at it. The build makes a small Java runtime from it with `jlink`. |
| Node | **24** with npm |
| Maven | 3.9 or newer |
| Rust | the stable toolchain via [rustup](https://rustup.rs) |
| Windows | Visual Studio Build Tools with "Desktop development with C++"; WebView2 (part of Windows 10 / 11) |
| macOS | Xcode command line tools (`xcode-select --install`); the app needs macOS 14 or newer |
| Linux | the Tauri packages, on Debian / Ubuntu: `libwebkit2gtk-4.1-dev libdbus-1-dev build-essential curl wget file libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev pkg-config` |

## Build the installer

From the repository root:

```bash
./scripts/build-desktop.sh            # Linux, macOS
```

```powershell
.\scripts\build-desktop.ps1           # Windows
```

The script builds the SPA, puts it into the backend JAR (`-Pdesktop`), makes the Java runtime
(`jlink`), copies both to `resources/` and runs `npx tauri build`. The installers end up in
`app-desktop/target/release/bundle/`:

| System | File |
|---|---|
| Windows | `nsis/Billery_<version>_x64-setup.exe` (installs for the current user), `msi/…` |
| macOS | `dmg/Billery_<version>_<arch>.dmg`, `macos/Billery.app` |
| Linux | `appimage/…AppImage`, `deb/…deb`, `rpm/…rpm` |

## Develop

```bash
npm install          # once: the Tauri CLI
npx tauri dev        # dev run; the window opens at http://127.0.0.1:8080
cargo check          # must pass after a change to src/lib.rs
```

How the window, the backend process, shutdown, backups and restore work together:
`AGENTS.md` (desktop shell) and [`docs/architecture/overview.md`](../architecture/overview.md).

The Java runtime: on Linux and macOS the script makes it with `jlink` from your JDK (with
`jdk.localedata`, German and English only, so amounts print as `1.234,56 €`); on Windows it
downloads the current Temurin 25 JRE from Adoptium.

Open problems of the desktop app (version number, signing, the unpinned Java runtime download on Windows,
updates) are tracked in #22 and #54.
