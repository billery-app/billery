//! Tauri shell: starts the bundled Spring Boot backend (H2 file database in the
//! per-user app-data folder), points the window at it, and stops it cleanly on exit.

use std::collections::hash_map::RandomState;
use std::fs::{self, OpenOptions};
use std::hash::{BuildHasher, Hasher};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::Manager;

/// The backend listens on 127.0.0.1 only (see application-desktop.properties).
/// The port is fixed because stored logo URLs contain it.
/// How long the backend may take to stop (incl. the backup on exit) before it is killed.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(60);
const BACKEND_URL: &str = "http://127.0.0.1:8080";

/// The running backend plus the secrets it was started with.
struct Backend {
    child: Mutex<Option<Child>>,
    shutdown_token: String,
}

fn strip_extended_prefix(path: &Path) -> PathBuf {
    let s = path.to_string_lossy();
    if let Some(stripped) = s.strip_prefix(r"\\?\") {
        PathBuf::from(stripped)
    } else {
        path.to_path_buf()
    }
}

fn log_to_file(path: &Path, msg: &str) {
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "[{}]", msg);
    }
}

/// 128 random bits as hex. `RandomState` is seeded from the OS RNG, which is
/// sufficient for a per-launch local secret without an extra crate.
fn random_token() -> String {
    let mut out = String::new();
    for _ in 0..2 {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_u32(std::process::id());
        hasher.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0),
        );
        out.push_str(&format!("{:016x}", hasher.finish()));
    }
    out
}

fn http_client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
        .expect("HTTP client")
}

/// Returns the `instance` field reported by whatever answers on the backend port,
/// or `None` when nothing answers.
fn health_instance(client: &reqwest::blocking::Client) -> Option<String> {
    let resp = client.get(format!("{BACKEND_URL}/api/health")).send().ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let body = resp.text().ok()?;
    let marker = "\"instance\":\"";
    let start = body.find(marker)? + marker.len();
    let end = body[start..].find('"')? + start;
    Some(body[start..end].to_string())
}

/// Waits until *our* backend (identified by `instance_id`) answers.
fn wait_for_backend(instance_id: &str, timeout: Duration) -> bool {
    let client = http_client();
    let start = Instant::now();
    while start.elapsed() < timeout {
        if health_instance(&client).as_deref() == Some(instance_id) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    false
}

fn start_backend(
    app: &tauri::AppHandle,
    log_file: &Path,
    instance_id: &str,
    shutdown_token: &str,
    ui_token: &str,
) -> Result<Child, String> {
    let resource_dir = app.path().resource_dir().map_err(|e| e.to_string())?;
    let resource_dir = strip_extended_prefix(&resource_dir);
    let app_data_dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&app_data_dir).map_err(|e| e.to_string())?;

    let java_bin = if cfg!(target_os = "windows") { "java.exe" } else { "java" };
    let java_path = resource_dir.join("runtime").join("bin").join(java_bin);
    let jar_path = resource_dir.join("app.jar");
    log_to_file(log_file, &format!("java: {} (exists: {})", java_path.display(), java_path.exists()));
    log_to_file(log_file, &format!("jar: {} (exists: {})", jar_path.display(), jar_path.exists()));
    if !java_path.exists() || !jar_path.exists() {
        return Err("Java-Laufzeit oder app.jar fehlt in der Installation.".into());
    }

    let db_url = format!(
        "jdbc:h2:file:{};MODE=MariaDB",
        app_data_dir.join("database").to_string_lossy()
    );
    let stderr = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(app_data_dir.join("backend-err.log"))
        .map_err(|e| e.to_string())?;

    Command::new(&java_path)
        .arg("-jar")
        .arg(&jar_path)
        // Run inside the app-data folder so relative paths (.env, logs) never land
        // in the install directory, which may be read-only.
        .current_dir(&app_data_dir)
        .env("SPRING_PROFILES_ACTIVE", "desktop")
        .env("DB_URL", &db_url)
        .env("FILES_PATH", app_data_dir.join("files"))
        .env("DESKTOP_INSTANCE_ID", instance_id)
        .env("DESKTOP_SHUTDOWN_TOKEN", shutdown_token)
        // The window's secret for the desktop-only endpoints (backups); see the window URL below.
        .env("DESKTOP_UI_TOKEN", ui_token)
        .stdout(Stdio::null())
        .stderr(Stdio::from(stderr))
        .spawn()
        .map_err(|e| e.to_string())
}

/// Asks the backend to shut down (so H2 closes the database file cleanly), and
/// kills it only if it is still running after a grace period. The backend makes
/// the backup on exit first (if switched on in the settings), so the grace period
/// is generous; a backup cut short leaves only a `.part` file, never a broken backup.
fn stop_backend(backend: &Backend) {
    let Ok(mut guard) = backend.child.lock() else { return };
    let Some(mut child) = guard.take() else { return };
    let _ = http_client()
        .post(format!("{BACKEND_URL}/api/desktop/shutdown"))
        .header("X-Shutdown-Token", &backend.shutdown_token)
        .send();
    let deadline = Instant::now() + SHUTDOWN_GRACE;
    while Instant::now() < deadline {
        if let Ok(Some(_)) = child.try_wait() {
            return;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    let _ = child.kill();
    let _ = child.wait();
}

/// Replaces the window content with an error message (German, user-facing).
/// Runs on a helper thread after a short delay, so it also works when called
/// during setup before the window has loaded.
fn show_error(app: &tauri::AppHandle, message: &str) {
    let app = app.clone();
    let message = message.to_string();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(1));
        show_error_now(&app, &message);
    });
}

fn show_error_now(app: &tauri::AppHandle, message: &str) {
    if let Some(window) = app.get_webview_window("main") {
        let text = message.replace('\\', "\\\\").replace('`', "'");
        let _ = window.eval(&format!(
            "document.documentElement.innerHTML = `<body style=\"font-family:sans-serif;padding:2rem\">\
             <h2>Billery konnte nicht starten</h2><p>{text}</p></body>`;"
        ));
    }
}

pub fn run() {
    let app = tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();
            let app_data_dir = app.path().app_data_dir()?;
            fs::create_dir_all(&app_data_dir)?;
            let log_file = app_data_dir.join("app.log");
            log_to_file(&log_file, "=== Billery Desktop starting ===");

            let instance_id = random_token();
            let shutdown_token = random_token();
            // Given to the backend and to our own window only (URL fragment, never sent over
            // HTTP): other programs on this computer reach 127.0.0.1 too, but not the backups.
            let ui_token = random_token();

            // Something already answers on our port: a dev backend, another
            // instance, or a leftover process. Never talk to it as if it were ours.
            if health_instance(&http_client()).is_some() {
                log_to_file(&log_file, "port 8080 already in use by another server");
                show_error(
                    &handle,
                    "Port 8080 wird bereits von einem anderen Programm verwendet \
                     (läuft das Programm schon, oder ein Entwicklungs-Backend?). \
                     Bitte das andere Programm beenden und erneut starten.",
                );
                return Ok(());
            }

            let child = match start_backend(&handle, &log_file, &instance_id, &shutdown_token, &ui_token) {
                Ok(child) => child,
                Err(e) => {
                    log_to_file(&log_file, &format!("start_backend failed: {e}"));
                    show_error(&handle, &e);
                    return Ok(());
                }
            };
            log_to_file(&log_file, &format!("backend pid {}", child.id()));
            app.manage(Backend {
                child: Mutex::new(Some(child)),
                shutdown_token,
            });

            std::thread::spawn(move || {
                let started = wait_for_backend(&instance_id, Duration::from_secs(60));
                log_to_file(&log_file, &format!("backend ready: {started}"));
                if started {
                    if let Some(window) = handle.get_webview_window("main") {
                        // ui_token is hex (random_token), safe inside the JS string literal.
                        let _ = window.eval(&format!(
                            "window.location.replace('{BACKEND_URL}/#desktop-token={ui_token}')"
                        ));
                    }
                } else {
                    show_error_now(
                        &handle,
                        "Der Server ist nicht gestartet. Details stehen in backend-err.log im Programmdatenordner.",
                    );
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|handle, event| {
        if let tauri::RunEvent::Exit = event {
            if let Some(backend) = handle.try_state::<Backend>() {
                stop_backend(&backend);
            }
        }
    });
}
