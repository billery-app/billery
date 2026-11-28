# Build environment of the desktop app on NixOS (#22): the system libraries Tauri 2 links against
# on Linux (WebKitGTK 4.1, GTK 3, libsoup 3, D-Bus via tao) and pkg-config to find them. Other
# distributions install the same as packages (README.md).
#
#   cd app-desktop && nix-shell
#   ../scripts/build-desktop.sh
#
# JDK 25, Node 24, Maven and Rust are taken from the system; add them below if they are not.
{ pkgs ? import <nixpkgs> { } }:

pkgs.mkShell {
  nativeBuildInputs = with pkgs; [
    pkg-config
    gobject-introspection
  ];

  buildInputs = with pkgs; [
    at-spi2-atk
    cairo
    dbus
    gdk-pixbuf
    glib
    gtk3
    harfbuzz
    librsvg
    libsoup_3
    openssl
    pango
    webkitgtk_4_1
  ];

  # The built app needs the GTK settings schemas when it is started from this shell.
  shellHook = ''
    export XDG_DATA_DIRS=${pkgs.gsettings-desktop-schemas}/share/gsettings-schemas/${pkgs.gsettings-desktop-schemas.name}:${pkgs.gtk3}/share/gsettings-schemas/${pkgs.gtk3.name}:$XDG_DATA_DIRS
  '';
}
