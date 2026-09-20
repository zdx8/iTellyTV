# iTellyTV

Android TV 13+ IPTV / 媒体播放器。设计继承自
[iTelly-macOS](https://github.com/zdx8/iTelly-macOS)，播放内核用
[Media3 / ExoPlayer](https://github.com/androidx/media) (Apache 2.0)
代替 libVLC，所以 APK 比 macOS 版小得多（4.2 MB 而不是 81 MB），**不传染
GPL**。

|  | iTelly-macOS | **iTellyTV** |
|---|---|---|
| 平台 | macOS 14+ (arm64) | **Android TV 13 / 14 / 15 / 16** |
| 播放内核 | libVLC（GPLv2+） | **Media3 / ExoPlayer（Apache 2.0）** |
| 协议 | HLS, RTSP, RTMP, UDP, HTTP, FTP | 同等（除 FTP, MMS） |
| APK 大小 | 81 MB | **4.2 MB**（R8 压缩 + resource shrink） |
| 签名 | — | **已签名**（CI 从 secrets 读 keystore） |
| License | GPL-2.0-or-later | **Apache 2.0** |

## 安装

到 [Releases](https://github.com/zdx8/iTellyTV/releases) 下载最新的
`iTellyTV-vX.Y.Z-release.apk`（**已签名 release build**，4.2 MB；也提供
带调试信息的 `-debug.apk`），用 adb 安装到 Android TV 设备：

```bash
adb install -r iTellyTV-v1.1.0-release.apk
adb shell am start -n com.example.itellytv/.ui.MainActivity
```

校验下载完整性：

```bash
shasum -a 256 --check iTellyTV-v1.1.0-release.apk.sha256
```

首次启动会提示"是否允许安装未知应用"——**设置 → 安全 → 允许此来源**。

## 订阅源

iTellyTV 默认加载您 home 网络上的 IPTV 订阅：

```
http://10.0.0.51:1905/interface.m3u?profile=keren
```

如果想换订阅，长按主页右上角的 **⟳ Reload** 按钮，输入新的 m3u URL 即可。

## 功能

- ✅ M3U / M3U8 播放列表（远程订阅 / 本地文件 / 单条流直连）
- ✅ 多播放列表管理（按 group-title 分组，自然排序，CCTV 在前）
- ✅ 长按 OK 收藏频道 / 取消收藏
- ✅ 数字键跳台（1-2-3 → 跳到第 123 个频道）
- ✅ D-pad 上下键静默切台，OK 选台
- ✅ 左键召唤频道列表抽屉（30s 自动隐藏）
- ✅ 自动重连退避（1s/2s/4s，最多 3 次）
- ✅ 15s 无进度看门狗（防止卡死）
- ✅ 错误连续失败自动跳下一频道；全失败显示"服务器挂了"
- ✅ 全屏播放器 + 边到边 UI（适配 Android 15+）
- ✅ API 23+ 兼容（Android 6.0+, 含 TV 13/14/15/16）

## 操作

### D-pad 键位

| 键 | 抽屋关 | 抽屋开 |
|---|---|---|
| OK / 中心 | 播放/暂停 | 选台（高亮 → 切到该频道） |
| ← (LEFT) | 召唤抽屋 | 收抽屋 |
| ↑ (UP) | 切上一个频道 | 在抽屋里**移高亮**（视频不变） |
| ↓ (DOWN) / → (RIGHT) | 切下一个频道 | 在抽屋里**移高亮**（视频不变） |
| Back | 退出 app | 收抽屋（再按退出） |
| 数字 0-9 | 跳到第 N 个频道 | 同左 |

### 完整流程示例

1. 打开 app → 加载订阅 → **自动播第一个频道**（看不到频道列表）
2. 按 **↓** → 静默切到下一个频道
3. 按 **←** → 左侧 30% 抽屉滑出，**当前频道蓝色高亮**
4. 按 **↓** → 抽屋里**光标下移**（视频不变）
5. 按 **OK** → 切到光标那个频道，抽屉自动关闭
6. 5 秒不按 → 抽屋自动消失
7. 长按 OK 在某频道 → 收藏 / 取消收藏（左侧出现 ★ 标记）

## 项目结构

```
iTellyTV/
├── app/
│   ├── build.gradle.kts          AGP 8.6.1, Kotlin 2.0.21, Media3 1.7
│   └── src/main/
│       ├── AndroidManifest.xml   Leanback + 双 intent-filter
│       └── java/com/example/itellytv/
│           ├── AppConfig.kt             默认订阅 URL + timeouts
│           ├── CrashLog.kt              全局崩溃 → 文件 + 显示
│           ├── iTellyApp.kt             Application (diag, crash handler)
│           ├── data/
│           │   ├── m3u/M3UParser.kt     完整移植 macOS 版 parser
│           │   ├── model/Entities.kt     Room + Parcelable ChannelEntity
│           │   ├── model/Channel.kt      Domain model
│           │   ├── model/NaturalSortKey.kt  "CCTV/湖系/其他中文/其他" 4 桶
│           │   ├── repository/ChannelRepository.kt
│           │   ├── repository/SubscriptionRefresher.kt   5s/15s/30s 退避
│           │   └── source/{Daos.kt, iTellyDatabase.kt}
│           ├── player/
│           │   ├── PlayerConfig.kt       1.5s 直播缓冲 / 4s VOD
│           │   ├── PlayerController.kt    ExoPlayer + 15s 看门狗
│           │   ├── ChannelDrawer.kt       左侧抽屉 (5s 自动隐藏)
│           │   ├── ChannelDrawerAdapter.kt
│           │   ├── ChannelOptions.kt      #EXTVLCOPT 解析 → DataSource headers
│           │   ├── Diagnostics.kt        离线回归断言（CI 用）
│           │   └── PlaybackActivity.kt   全屏播放 + 抽屉 + D-pad
│           └── ui/
│               ├── MainActivity.kt        主页（频道列表 + Reload 按钮）
│               ├── MainViewModel.kt       ViewModel（Activity 重建保护）
│               ├── ChannelIndexBuffer.kt  数字键 buffer
│               ├── ChannelRowAdapter.kt
│               ├── ChannelContextMenu.kt  长按菜单
│               ├── EdgeToEdgeInsets.kt    Android 15+ edge-to-edge
│               └── ColorExt.kt            状态色集中点
├── docs/
│   ├── index.html                  GitHub Pages 官网
│   └── styles.css
├── scripts/
│   ├── diagnose.sh                 模拟 macOS 的 --diagnose
│   └── test-on-device.sh           真机测试脚本
├── build.gradle.kts                 AGP 8.6.1, Kotlin 2.0.21
├── settings.gradle.kts
└── README.md
```

## 架构亮点（移植自 macOS）

iTelly-macOS README 列了"5 个值得记录的实现要点"——iTellyTV 全部继承：

1. **DataSource 工厂包装**：按 macOS 的 "VLC_PLUGIN_PATH before libvlc_new" 思想，
   在 `PlayerController.prepare()` 一次性建好 `DefaultHttpDataSource.Factory`
   （含超时 + User-Agent + #EXTVLCOPT headers），再传给 `ExoPlayer.Builder()`。

2. **重连退避 + 上限**：1s/2s/4s 退避，最多 3 次——**修复了 macOS "自动重连永不停"那个 bug**。
   iTellyTV 额外加 "连续 N 个频道失败 → 停止循环，显示 '服务器挂了'"。

3. **音量范围一致**：Media3 `volume` 0.0f-1.0f——和 macOS 的"避免一处用 0-1、另一处用 0-100"教训一致。

4. **渲染视图长期存活**：视频 SurfaceView **永不替换**（macOS 的 VLCCAOpenGLLayer 教训）。
   切频道只换 MediaItem，不重建 Surface。

5. **加载控件 set 完再 build**：LiveConfig / BufferDurations 在 `ExoPlayer.Builder().build()`
   **之前**全部设好。macOS 项目犯过这个错。

## Build

```bash
cp .env.sh.example .env.sh && $EDITOR .env.sh   # 填 JDK 17 + Android SDK 路径
source .env.sh
./gradlew :app:assembleDebug                    # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                  # → app-release.apk（已签名，4.2 MB）
./gradlew :app:testDebugUnitTest                # 70 unit tests
./gradlew :app:lint                             # Android Lint
./scripts/diagnose.sh                           # self-test (mirror macOS --diagnose)
```

### Release 签名（本地）

CI 从 GitHub Secrets 读 keystore；本地构建需要自己放一份：

```bash
# 1. 生成 keystore（一次性；.keystore 文件不要提交）
keytool -genkeypair -v \
  -keystore app/signing/itellytv-release.keystore \
  -alias itellytv-release -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass 'YOUR_PASSWORD' -keypass 'YOUR_PASSWORD' \
  -dname "CN=iTellyTV, OU=zdx8, O=zdx8, L=Beijing, ST=Beijing, C=CN"

# 2. 复制模板并填密码
cp keystore.properties.example keystore.properties && $EDITOR keystore.properties

# 3. 构建
./gradlew :app:assembleRelease
```

没有 `keystore.properties` 时 release 构建仍会跑，只是产出 unsigned APK。

### CI 签名（GitHub Actions）

仓库需要 3 个 secret：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i app/signing/itellytv-release.keystore` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS_PASSWORD` | key 密码 |

```bash
base64 -i app/signing/itellytv-release.keystore | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS_PASSWORD
```

### Build 要求

| 工具 | 版本 |
|---|---|
| JDK | 17 (Temurin 推荐) |
| Android SDK | API 23-35, build-tools 34+ |
| Kotlin | 2.0.21 (在 `build.gradle.kts` 自动下) |
| AGP | 8.6.1 (在 `build.gradle.kts` 自动下) |

## 测试

```bash
./gradlew :app:testDebugUnitTest
# 70 tests, 0 failures
```

覆盖：
- M3U parser（14 个测试，含引号内逗号、EXTVLCOPT 白名单、UTF-8 BOM、GB18030 fallback）
- Natural sort（14 个测试，含 "CCTV" → "湖系" → "其他中文" → "其他" 4 桶排序）
- Channel options 解析（9 个测试，含 proxy / referer 变体）
- Player config（7 个测试，含重连退避 + buffer 大小）
- ChannelIndexBuffer（5 个测试，含数字键 buffer + 超时）
- ChannelDrawerAdapter（5 个测试，isRowSelected 纯函数）
- PlayerController（12 个测试，error 分类 + 重连退避计算）
- ChannelEntityDisplayName（4 个测试，tvgName 优先）

## 已知问题 / 路线图

| 状态 | 任务 | 版本 |
|---|---|---|
| ✅ | 自动播第一个频道 | 1.0.0 |
| ✅ | 上下键切台 | 1.0.0 |
| ✅ | 左侧抽屉 + 自动隐藏 | 1.0.0 |
| ✅ | 数字键跳台 | 1.0.0 |
| ✅ | 重连退避（1s/2s/4s，上限 3 次） | 1.0.0 |
| ✅ | 错误连续失败保护 | 1.0.0 |
| ✅ | Android 15 edge-to-edge | 1.0.0 |
| ✅ | 签名 release APK（CI 从 secrets 读 keystore） | 1.1.0 |
| ✅ | ProGuard / R8 minify（11 MB → 4.2 MB） | 1.1.0 |
| ✅ | MediaSession + 前台服务 | 1.1.0 |
| 🔲 | 把 ExoPlayer 完整迁移进 PlaybackService（当前 service 是占位） | P2 |
| 🔲 | EPG 节目单（XMLTV） | P3 |
| 🔲 | 媒体库（本地视频 / USB / SMB） | P3 |
| 🔲 | 多播放列表切换 UI | P3 |
| 🔲 | Leanback → Compose for TV 迁移 | P4 |

## 许可证

**Apache 2.0** — 见 [LICENSE](LICENSE)。

内嵌的 Media3 / ExoPlayer 由 Google 以 Apache 2.0 授权，可与本应用独立分发。

## 致谢

- 设计架构来自 [iTelly-macOS](https://github.com/zdx8/iTelly-macOS)
- 播放引擎 [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) (Apache 2.0)
- IPTV 订阅协议（m3u / m3u8） — 公开标准
