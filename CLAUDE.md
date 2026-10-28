# 一键理桌 TidyHome · 开发协作规则

> 给所有参与开发的 Agent（Opus 决策 / Sonnet 写代码 / Haiku 整理数据）读的入口文件。
> 用户是**编程零基础**的产品负责人：只做产品方向决策、在真机上测试。所有工程决策由 Opus 做并记入 `docs/03-决策记录.md`。

## 一句话

安卓 App：用户点一下，自动把原生桌面上乱放的图标**按类别归进文件夹**。不换桌面、不联网、不用大模型。

## 文档索引（改代码前先读对应文档）

| 文档 | 内容 | 谁维护 |
|---|---|---|
| `docs/01-产品设计.md` | 做什么、不做什么、用户流程 | Opus（产品决策需产品负责人拍板） |
| `docs/02-技术方案.md` | 架构、模块、执行引擎原理 | Opus |
| `docs/03-决策记录.md` | 每个关键决策的理由（ADR） | Opus |
| `docs/04-路线图与验收.md` | 阶段划分、每阶段验收标准、当前进度 | Opus |
| `docs/05-真机测试指南.md` | 给测试者：装 APK、开权限、整理流程、常见情况 | Opus |

## 技术栈（锁定，改动需先记 ADR）

- Kotlin 2.0.21 · AGP 8.8.2 · Gradle 8.13（wrapper）· JDK 17
- compileSdk / targetSdk 35 · minSdk 26（`dispatchGesture` 连续手势需要 API 26）
- **零第三方依赖**：只用 Android 框架 API + Kotlin 标准库，不引 AndroidX/Compose。
  理由：本机离线缓存可直接构建；无障碍服务不需要 UI 框架。确需引入时先写 ADR。
- 包名 `io.github.tidyhome`

## 构建与验证（Windows 本机）

- **一律用 PowerShell 工具跑 Gradle**，不要在 Git Bash 里用 `cmd //c`（会假成功）。
- 构建：`.\gradlew.bat assembleDebug`
- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- **宣布构建成功前必须验证产物**：APK 存在 + 修改时间是本次构建 + 记录 SHA256。
- 单测：`.\gradlew.bat :app:testDebugUnitTest`，结果看 `app\build\test-results\testDebugUnitTest\*.xml`。
  本机 PATH 有一段被双引号包住的条目，会让单测进程报 `ClassNotFoundException: Files\Common`；
  `gradlew.bat` 开头已加了去引号的处理。若仍复现，先 `.\gradlew.bat --stop`，清掉之前用脏 PATH 启动的守护进程。
- 安装到手机：`adb install -r <apk>`
- 日志：`adb logcat -s TidyHome:*`

## 代码规范

- 注释、日志、界面文案用**中文**；标识符用英文。
- 日志统一 `Log.i("TidyHome", ...)`，执行引擎的每一步（找到什么节点、做了什么动作、结果如何）都要打日志——
  产品负责人无法调试，日志是我们唯一的远程眼睛。
- 所有毫秒/坐标/阈值写成命名常量，注释说明单位和来源（"实测于 Pixel Launcher"或"估计值待实测"）。
- 无障碍节点用完要 `recycle()`（API 33 以下）；不持有节点跨越等待。
- 任何会改动用户桌面的操作，必须可被**音量键中止**。

## 红线

1. 不联网、不申请网络权限、不收集数据（开源项目的信任基础）。
2. 不改动 Dock 栏（hotseat）、小组件、应用抽屉。只动桌面工作区（workspace）的图标。
3. 执行前必须有预览确认；不允许"静默整理"。
4. git commit message 一律中文，结尾带 Co-Authored-By 行。

## Agent 分工

- **Opus**：定方案、写/改 docs、拆任务卡、审代码、本机构建验证、向产品负责人汇报。
- **Sonnet**：按任务卡写 Kotlin 代码，只改任务卡列出的文件；完成后报告"改了什么、没做什么、哪里不确定"。
- **Haiku**：数据类工作（分类词典、关键词表、文案清单），产出带置信度标注。
- 子 Agent 不自行 commit，由 Opus 审查后统一提交。
