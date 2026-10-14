# DiPlay G3

小鹏 G3 / XiaoPeng D21（Android 9）专用的 DiPlay 分支。

## 保留的功能

- 仅使用车机自己的普通 WPA2 Wi-Fi 热点连接 iPhone。
- CarPlay 音视频、触摸操作、iPhone 蓝牙选择和本地认证。
- 在应用中读取、修改热点名称和密码，同步小鹏投屏助手的 `hotspot_name_and_pass` 缓存。
- 修改前校验输入；明确确认后才重启正在使用的热点；写入后校验，并在失败时尝试回滚。
- 同样的热点配置只校验或修复缓存，不重启热点。
- 可选择开机打开 DiPlay G3。

热点只有网络名称（SSID）和密码，没有单独的登录账号。
SSID 限 1–32 个 UTF-8 字节；密码限 8–63 个可打印 ASCII 字符。
由于厂商缓存的格式限制，两者均不能包含 `|`；不允许控制字符。
密码默认隐藏，显示密码时禁止截屏；密码不出现在应用日志中。

Wi-Fi Direct、其他局域网模式、临时热点、USB 投屏入口、板载认证选择、其他应用模块和原项目自动更新入口已移除。
内部协议兼容类型与部分底层工具仍保留；运行时拒绝非车机热点连接与非本地认证。
界面支持简体中文和英文。

## 安装与授权

应用包名：`com.techeek.diplay.g3`，与原版 DiPlay 可以同时安装。
一次只运行一个投屏应用。

安装 APK 后打开 **授权热点配置**。车机需要启用本地网络 ADB（D21 端口 5050）；
出现调试授权提示时由用户确认。应用只连接 `127.0.0.1:5050`。
授权动作只向本应用授予 `WRITE_SETTINGS` 与 `WRITE_SECURE_SETTINGS`，用于热点和指定厂商缓存。
后台读取或修改从不请求新的 ADB 授权。

普通应用不能取得某些固件的 `OVERRIDE_WIFI_CONFIG` 签名权限。
应用优先使用系统 Wi-Fi 接口；被拒绝时，通过用户已授权的本地 ADB 运行 APK 自带的固定热点读取/写入工具。
该工具只接受读取或经过验证的 Wi-Fi 配置写入，不能接收任意用户 shell 命令；不修改系统文件。

也可在电脑通过已授权的 ADB 安装并初始化：

```sh
adb -s CAR_ADDRESS:5050 install -r DiPlay-G3.apk
adb -s CAR_ADDRESS:5050 shell appops set com.techeek.diplay.g3 WRITE_SETTINGS allow
adb -s CAR_ADDRESS:5050 shell pm grant com.techeek.diplay.g3 android.permission.WRITE_SECURE_SETTINGS
```

在应用中选择已与车机蓝牙配对的 iPhone，手机加入车机热点后点击连接。
修改热点会使现有客户端断开；先断开 CarPlay，再确认保存并重新连接手机、电脑。
显式使用原厂投屏助手的“刷新密码”仍会主动更换密码；这不属于本应用可以禁止的操作。

## 构建

需要 JDK 25、Android SDK 37.0、Build Tools 36.0.0、NDK 28.2.13676358。
G3 APK 为 ARM64，最低 Android 9；目标 API 28 保留 D21 固件需要的旧式热点接口。

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

普通源代码构建不携带 CarPlay 认证私钥，因此不能独立连接 iPhone。
本地完整测试版需要通过 `DIPLAY_AUTH_ASSETS_DIR` 指向仓库外的认证资产目录：

```sh
DIPLAY_AUTH_ASSETS_DIR=/private/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

目录结构与限制见 [构建文档](docs/BUILD.md)。认证资产、Android 签名密钥、真实热点密码和 APK 均不得提交到公开源码。

## 验证范围

已完成的本地检查与实车待验证项目见 [验证记录](docs/G3_VALIDATION.md)。

设备热点读写、重启、厂商投屏助手重新启动后的配置一致性，以及实际 iPhone 音视频投屏，都需要 G3 实机确认。
自动测试不能代替真实 CarPlay 会话。车机进入真正休眠仍可能清理第三方应用，开机启动不能保证锁车后持续运行。

## 来源与许可证

Fork 自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。
协议实现源于 xcertplay（GPL-3.0），部分界面源于 DiAuto（AGPL-3.0）。
保留原作者、版权、LICENSE 与 [第三方授权声明](docs/THIRD_PARTY_NOTICES.md)。
原项目关于实验性 CarPlay 认证身份的说明仍然适用；本分支不会将认证私钥提交到 Git。
