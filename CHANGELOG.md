## 1.0.3

* fix: no such module 'XRay' 

* feat: code formatted

## 1.0.2

*   **Refactor**: Migrated to a Federated Plugin architecture.
    *   Split into `flutter_vless` (app-facing), `flutter_vless_platform_interface` (common), and `flutter_vless_android` (Android implementation).
    *   This structure improves maintainability.

*   **Android**:
    *   **Migration to Kotlin**: Complete rewrite of Android native code from Java to Kotlin.
    *   **16KB Page Size**: Added support for Android devices with 16KB page sizes (API 35+).

*   **Docs**: Added comprehensive documentation to Android native code.

## 1.0.1

* feat: upgrade xray version and update documentation

* refactor: modify V2rayCoreManager to use CoreController and improve lifecycle management

* fix: enhance error handling in V2rayProxyOnlyService and V2rayVPNService

* style: adjust spacing in main.dart for better UI layout

* docs: improve descriptions and comments in V2ray services for clarity

* chore: update pubspec.yaml with additional tags and improved description

## 1.0.0

* init
