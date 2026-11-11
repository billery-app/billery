fn main() {
    // Workaround for webkit2gtk Wayland rendering crashes on Linux
    // (Error 71 protocol error / GBM buffer failure / WebLoaderStrategy crash)
    if std::env::var("WEBKIT_DISABLE_COMPOSITING_MODE").is_err() {
        std::env::set_var("WEBKIT_DISABLE_COMPOSITING_MODE", "1");
    }

    // Capture any panic to a file
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("Panic: {}\n", info);
        let _ = std::fs::write(
            std::env::temp_dir().join("billery-panic.log"),
            &msg,
        );
    }));

    billery_lib::run()
}
