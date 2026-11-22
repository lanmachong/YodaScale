# YodaScale 体重记录

> **无广告 · 无账号 · 数据全本地** 的极简安卓体重/体成分记录 App。
> 通过蓝牙广播读取 Yoda0 / 芯海（Chipsea）OKOK 1.1 协议的体重秤，体重数据只存在手机本地。
> v2.1.0 起新增应用内 OTA，**仅联网检查 GitHub 更新和下载安装包，不上传任何个人数据**（详见下方「关于权限」）。

当前版本：**v2.1.0**（`versionCode 3`）。变更记录见 [CHANGELOG.md](CHANGELOG.md)，后续规划见 [ROADMAP.md](ROADMAP.md)。

## 功能

- 蓝牙广播读取体重（无需配对、无需连接）
- 大字显示当前体重（统一换算成 kg）
- **身体资料设置**：身高、年龄、性别（本地保存）
- **8 项身体成分估算**：体脂率、BMI、肌肉量、水分、骨量、基础代谢、内脏脂肪、代谢年龄
- **月度对比**：本月平均 vs 上月平均（体重 + 体脂率变化）
- 历史记录列表（时间 + 体重 + 体脂率）
- 导出 CSV（含全部派生指标）/ 清空历史
- **扫描自动停止**：锁定读数后 3 秒停扫，90 秒无读数兜底停扫，省电
- **应用内 OTA**：从 GitHub Releases 检查更新并下载安装
- 体重/身体资料**只存本地、从不上传**，无广告、无账号

## 构建

仓库已内置 Gradle Wrapper（`gradle-wrapper.jar`），任意机器**只需 JDK 17**即可构建，无需预装 Gradle：

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows (PowerShell)
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

也可用 Android Studio 直接打开本项目构建（首次会自动下载 Gradle 与依赖）。Android 12+ 需 `targetSdk 34`，项目已配置。

> GitHub 上推送到 `main` / `master` 会自动触发 [CI](.github/workflows/build-apk.yml)：构建 Debug APK + lint，并在 Actions 产物中保留 90 天。

### 本机离线工具链（可选）

若机器预置了 `.buildtools`（JDK + Gradle + Android SDK），设置环境变量后构建：

```powershell
$env:JAVA_HOME = "<工具链目录>\jdk-17.0.21+9"
$env:ANDROID_HOME = "<工具链目录>\sdk"
<工具链目录>\gradle-8.5\bin\gradle.bat assembleDebug --no-daemon
```

> 注意：AGP 对含中文的项目路径会报非 ASCII 警告。GitHub Actions 为纯英文路径不受影响；
> 本机若放在中文目录且构建失败，可在 `gradle.properties` 加 `android.overridePathCheck=true`。

> 国内加速：首次构建需下载 Gradle 8.5 发行包，若 `services.gradle.org` 超时，
> 可把 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 临时改为
> `https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip`（或阿里云镜像）后再构建。
> 注意：这是本机临时改动，提交时请勿把镜像地址写进仓库，以免影响 GitHub Actions。

## 使用

1. 手机打开蓝牙（Android 6–11 还需打开定位）
2. 打开 App → 点「身体资料」填身高、年龄、性别（首次建议先填）
3. 点「开始扫描」→ **光脚站上秤**，等 LCD 数字稳定（锁定）
4. App 自动记录体重并算出各项指标，3 秒内同一体重不重复入库
5. 历史列表 / 月度对比 / 导出 CSV 随时可看

## 关于体脂数据的口径（重要）

此款 Yoda0 秤广播的电阻值恒为 `5000`，超出有效 BIA 范围（150–1200Ω），**不是真实阻抗测量**。

因此 App 按与 okok 相同的思路，用「体重 + 身高 + 年龄 + 性别」的公开公式做估算：

| 指标 | 公式 |
|---|---|
| 体脂率 | Deurenberg：`1.2×BMI + 0.23×年龄 − 10.8×性别 − 5.4` |
| 基础代谢 | Mifflin-St Jeor：`10×体重 + 6.25×身高 − 5×年龄 + 5(男)/−161(女)` |
| 水分 | 去脂体重(LBM) × 0.73 / 体重 | 
| 骨量 | 体重 × 约 4%（男 4.2–4.4%，女 3.9–4.1%） | v2.1.0 修正，旧版用 LBM×0.042 偏小 |
| 肌肉量 | 去脂体重 − 骨量 |
| 内脏脂肪 | `体脂率×0.55 − 4 + 年龄×0.08` |
| 代谢年龄 | `年龄 + (体脂率 − 同龄均值)×0.8`，同龄均值男 20% / 女 28% |

**这些是估算值，仅供自己纵向追踪趋势，不代表真实体成分。** 界面和 CSV 里都这么标注。想要真体脂只能换带真阻抗的秤。

代码见 `app/src/main/java/com/example/yodascale/BodyMetrics.kt`。

## 协议说明（供二次开发）

广播数据位于 Manufacturer Specific Data，company id `0x10C0`，13 字节布局：

| 字节 | 含义 |
|---|---|
| 0–1 | 体重原始值，大端 uint16 |
| 2–3 | 电阻（此款恒 5000） |
| 6 | bit3–5 单位码，bit0 锁定标志 |
| 7–12 | MAC 地址（反序） |

单位码：`4`=kg(÷100)、`5`=斤(÷10)、`6`=lb(÷10)、`7`=英石:磅。

核心解析见 `ScaleParser.kt`。

## 扩展研究

- [多设备接入与 WiFi 可行性研究](docs/多设备接入与WiFi可行性研究.md)：协议族图谱（Chipsea OKOK / MIBCS / QN-Scale 广播型 + 连接型 + 加密型）、`ScaleAdapter` 适配器架构、WiFi 三路线对比、BIA 公式系数表。
- 结论摘要：优先接入 MIBCS（小米）、QN-Scale（Renpho）两个广播型协议族即可覆盖大部分贴牌秤；**WiFi 现阶段不做**，会破坏「无网络权限」这个核心优势。
- 具体实施计划见 [ROADMAP.md](ROADMAP.md) 的 v3.0 / v4.0。

## 关于权限（v2.1.0 起）

本版为满足应用内更新需求，新增了 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES` 权限：

- `INTERNET`：**仅**用于请求 GitHub Releases API 查询最新版本、下载 APK。**不上传任何体重、身高、年龄、性别或设备信息**。
- `REQUEST_INSTALL_PACKAGES`：Android 8+ 安装 APK 必需，首次更新会跳转系统设置让你授权「安装未知应用」。
- 除更新外的所有功能（扫描、计算、存储、导出）**完全离线**。

上传仓库后，在 `app/src/main/java/com/example/yodascale/UpdateManager.kt` 顶部把
`REPO_OWNER` / `REPO_NAME` 改成你的 GitHub 用户名与仓库名，OTA 即可指向你的 Release。

> 若你更看重「完全没有网络权限」，可继续用 v2.0.0（无 INTERNET 权限），不要升级到本版。

## 许可

[MIT License](LICENSE) — 可自由使用、修改、商用，保留版权声明即可。
