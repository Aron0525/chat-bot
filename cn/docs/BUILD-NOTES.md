# 本地编译备忘（Windows）

## E 盘上 aapt2 会失败（本机实测）

如果在 `E:\...` 下编译，`:app:processDebugResources` 会稳定报：

```
ERROR: AAPT: ...\intermediates\stable_resource_ids_file\debug\processDebugResources\stableIds.txt:
  error: failed to open: 参数无效 (13)
```

已排除的因素：磁盘空间够、路径全 ASCII、没有 junction、ACL 正常、大小写敏感属性关闭；
换成 SDK 自带的 `aapt2.exe`（`-Pandroid.aapt2FromMavenOverride=...`）也一样。
把同一份代码放到 C 盘编译就正常。结论：这是这台机器 E 盘的环境问题，不是工程问题。
**本地编译请在 C 盘做，或者直接走 GitHub Actions（`release-apk.yml`）。**

## 本地编译命令（在 C 盘副本的 `cn/` 目录下）

```powershell
$env:ANDROID_HOME    = 'C:\path\to\.android-sdk'     # 需含 platforms;android-35、build-tools;35.0.0
$env:GRADLE_USER_HOME = 'C:\path\to\.gradle-home'
$env:JAVA_HOME       = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'

.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.jev.probe.update.*"
```

注意：
- `gradlew` 会去 `services.gradle.org` 下 Gradle 8.9，本机这条链路经常超时；
  已解压好的发行版可以放在外面，直接用它：`<gradle-8.9>/bin/gradle.bat --no-daemon ...`。
- 依赖来自 `google()` / `mavenCentral()`，首次会下几百 MB。

## 发版（在线更新的供给端）

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 在仓库 Secrets 里配好：`ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、
   `ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
3. 打 tag 并推：`git tag v1.8 && git push origin v1.8`。
4. `release-apk.yml` 会构建签名 APK 并挂到该 Release 上；tag 与 `versionName` 不一致会直接失败。
5. 客户端读的是 `releases/latest`，所以**正式版**才会被看到（草稿/预发布不算）。

签名必须和已安装版本一致，否则系统拒绝覆盖安装。
