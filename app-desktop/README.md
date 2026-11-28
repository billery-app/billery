# Billery Desktop

The desktop app: Billery as a program on your own computer, without a server. A Tauri 2 window
(`src/lib.rs`) starts the bundled backend (Spring Boot with an H2 database file and its own Java
runtime) on `127.0.0.1:8080` and shows it; nothing is reachable from the network.

There is **no ready-made download yet**: the installer is built from this repository on the
operating system it is for (Windows on Windows, macOS on a Mac, Linux on Linux).

## What you need

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
.\scripts\build-desktop.ps1           # Windows (see "Known problems")
```

The script builds the SPA, puts it into the backend JAR (`-Pdesktop`), makes the Java runtime
(`jlink`), copies both to `resources/` and runs `npx tauri build`. The installers end up in
`app-desktop/target/release/bundle/`:

| System | File |
|---|---|
| Windows | `nsis/Billery_<version>_x64-setup.exe` (installs for the current user), `msi/…` |
| macOS | `dmg/Billery_<version>_<arch>.dmg`, `macos/Billery.app` |
| Linux | `appimage/…AppImage`, `deb/…deb`, `rpm/…rpm` |

## Install and start

- **Windows:** run the `…-setup.exe`. The installer is not signed: SmartScreen warns, "More info"
  → "Run anyway".
- **macOS:** open the `.dmg` and drag Billery into Applications. The app is not signed: open it the
  first time with right click → Open (or `xattr -cr /Applications/Billery.app`).
- **Linux:** make the AppImage executable (`chmod +x`) and start it, or install the `.deb` / `.rpm`.

The first start takes a few seconds (the backend starts). Enter the company details (Einstellungen
→ Inhaber) before the first document; the introductory tour starts while there are no documents.

## Where the data is

Everything lives in the app's data folder (identifier `de.billery.desktop`):

| System | Folder |
|---|---|
| Windows | `%APPDATA%\de.billery.desktop` |
| macOS | `~/Library/Application Support/de.billery.desktop` |
| Linux | `~/.local/share/de.billery.desktop` |

Inside: `database*` (H2), `files/` (logos and uploads), `backups/` (unless another backup folder
is chosen), `app.log` / `backend-err.log`, and the keys of the backup signature and the mail
passwords. **Uninstalling keeps this folder**; back it up or use Einstellungen → Datensicherung
before you move to another computer.

## Develop

```bash
npm install          # once: the Tauri CLI
npx tauri dev        # dev run; the window opens at http://127.0.0.1:8080
cargo check          # must pass after a change to src/lib.rs
```

How the window, the backend process, shutdown, backups and restore work together:
`AGENTS.md` (desktop shell) and `docs/architecture/overview.md`.

The Java runtime: on Linux and macOS the script makes it with `jlink` from your JDK (with
`jdk.localedata`, German and English only, so amounts print as `1.234,56 €`); on Windows it
downloads the current Temurin 25 JRE from Adoptium.

## Known problems (#22)

- The version in `tauri.conf.json` / `package.json` is `1.0.0`, not the release version.
- Installers are not signed (Windows, macOS).
- The Windows script downloads the latest Temurin 25 JRE without a fixed version or checksum.
