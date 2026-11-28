# Billery Desktop

The desktop app: Billery as a program on your own computer, without a server. A Tauri 2 window
(`src/lib.rs`) starts the bundled backend (Spring Boot with an H2 database file and its own Java
runtime) on `127.0.0.1:8080` and shows it; nothing is reachable from the network.

Installing needs nothing besides the installer: it brings its own Java runtime, and the Linux
packages (`.deb`, `.rpm`) declare the libraries they need, so the package manager installs them;
the AppImage carries them itself. There is **no ready-made download yet** (the release workflow
does not build the installers so far): until then, build the installer yourself, see
[`docs/guides/desktop.md`](../docs/guides/desktop.md), which also covers developing the app.

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
