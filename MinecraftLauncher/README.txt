This folder is reserved for the Minecraft: Java Edition launcher for Android.

Nothing here yet - it's just the place for it. Not wired into the project or the
build yet (not added to settings.gradle.kts), so it has no effect on the app
until the real files are in and it's actually hooked up.

She confirmed (2026-10-19) it will be full launcher source, PojavLauncher-style:
Kotlin + Java source, JRE builds, native libs, JNA, app_runtime, LWJGL.
So this becomes its own Gradle module here (like RenexaMC/ and app/), wired into
settings.gradle.kts once the real files land - not just assets, and not a bundled
APK.

The entry point on the app side is already done (in 1.5.8): a "RenexaLauncher"
card on the home page opens a page-launcher screen (currently a "coming soon"
placeholder). Once this module exists and builds, that screen is where it gets
hooked up (a WebView bridge call or similar to launch it).
