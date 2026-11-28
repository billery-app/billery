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
./scripts/build-desktop.sh            # Linux, macOS: every installer type of this system
./scripts/build-desktop.sh deb        # only the named types (deb rpm appimage nsis msi dmg)
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
npx tauri dev        # dev run (needs resources/ from a build-desktop run)
cargo check          # must pass after a change to src/lib.rs
```

## Window and native functions

- **Loading page.** The window starts on the page bundled in the app (`splash/index.html`,
  `build.frontendDist`; logo, progress bar, German or English by the system language) while the
  backend starts; `src/lib.rs` waits for `/api/health` with this launch's instance id and then
  switches the window to `http://127.0.0.1:8080/#desktop-token=…`. Errors (port taken, backend
  not started) replace the loading page with a message.
- **No console window on Windows.** `src/main.rs` builds the release as a Windows GUI program
  (`windows_subsystem = "windows"`) and the Java process is started with `CREATE_NO_WINDOW`;
  the backend's output goes to `backend-err.log` in the data folder. A debug build
  (`npx tauri dev`) keeps the console.
- **Folder picker and "show in folder".** The plugins `dialog` and `opener` are registered in
  `src/lib.rs`; `capabilities/app-pages.json` allows the pages of `http://127.0.0.1:8080/*`
  exactly two calls (`dialog:allow-open`, `opener:allow-reveal-item-in-dir`), reached through
  `window.__TAURI__` (`app.withGlobalTauri`). The SPA uses them only through
  `frontend/src/services/desktopApp.ts` (`isDesktopApp`, `pickFolder`, `showInFolder`); in a
  browser they do nothing. Used by Einstellungen → Datensicherung (choose the backup folder,
  show a backup) and Daten & Export: the GoBD export goes into a chosen folder
  (`POST /api/desktop/export/gobd`, `DesktopExportController`, with the window token; written as
  `.zip.part`, renamed when complete, never overwriting a file), because the app window has no
  download folder.
- **Icons.** `icons/` is generated from `icons/source-1024.png` (`npx tauri icon
  icons/source-1024.png`, then remove the Android / iOS / Square files, which are not bundled).

## Release

`.github/workflows/desktop-release.yml` builds the installers with `build-desktop.sh` on Linux
(`ubuntu-22.04`: deb, rpm, AppImage), Windows (NSIS) and macOS (Apple silicon, dmg), the version
from the tag written into `tauri.conf.json`. It runs only

- from `release.yml` for a release tag, whose job `desktop-publish` attaches the installers and
  `SHA256SUMS-desktop.txt` to the GitHub release, or
- by hand (Actions → Desktop installers → Run workflow, with a version): the installers are
  workflow artifacts for 7 days, nothing is published.

One run takes about 20 minutes per runner; the macOS runner is billed tenfold and Windows
twofold, so try it by hand only when the build itself changed. The installers are not signed.

How the window, the backend process, shutdown, backups and restore work together:
`AGENTS.md` (desktop shell) and [`docs/architecture/overview.md`](../architecture/overview.md).

The Java runtime: on Linux and macOS the script makes it with `jlink` from your JDK (with
`jdk.localedata`, German and English only, so amounts print as `1.234,56 €`); on Windows
`build-desktop.ps1` downloads the current Temurin 25 JRE from Adoptium (the release workflow uses
`build-desktop.sh` with the JDK of `setup-java` on every system instead).

Open problems of the desktop app (version number, signing, the unpinned Java runtime download on Windows,
updates) are tracked in #22 and #54.
