import 'flutter_vless_android.dart';

/// Registers the Android implementation.
/// This is called automatically when the package is imported.
class FlutterVlessAndroidLoader {
  static void _register() {
    FlutterVlessAndroid.registerWith();
  }
}

// Auto-register when package is loaded
final _autoRegister = FlutterVlessAndroidLoader._register();
