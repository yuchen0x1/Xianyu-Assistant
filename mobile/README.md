# 闲鱼卖家运营控制台移动端

这是一个 Flutter 客户端，复用电脑版的 Web 控制台。启动后在右上角“服务设置”中填写电脑服务地址，例如 `http://192.168.1.100:12400`，手机和电脑需要处于同一局域网。

## 构建

```bash
flutter pub get
flutter build apk --release --build-name 3.5.21 --build-number 3521
flutter build appbundle --release --build-name 3.5.21 --build-number 3521
```

版本号以 `mobile/pubspec.yaml` 的 `version:` 为准，构建时可省略 `--build-name/--build-number` 直接沿用。

Windows 可以构建 Android APK/AAB。iOS 工程可在 macOS + Xcode 中打开 `ios/Runner.xcworkspace` 后签名构建；Windows 无法生成签名 `.ipa`。
