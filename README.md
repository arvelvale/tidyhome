# 一键理桌 TidyHome

点一下，自动把安卓桌面上乱放的图标按类别归进文件夹。

**[落地页 →](https://arvelvale.github.io/tidyhome/)** · 开发中，**还没有安装包**

- **不用换桌面**：直接整理你现在的桌面，靠无障碍服务读取并模拟手指拖动
- **完全离线**：不申请网络权限，应用列表不会离开你的手机；归类靠本地词典，不用大模型
- **先预览再动手**：整理前能看到并调整分类；执行中按任意音量键立刻停下
- **不拆你已有的文件夹**：桌面上有同名文件夹就直接放进去

> English: TidyHome sorts the scattered icons on an Android home screen into category folders by reading the real home screen through the accessibility service and simulating finger drags. Offline, no network permission, stoppable with a volume key. In development; verified on a OnePlus 13 / ColorOS launcher only. No installable build yet.

## 现状（如实）

| | |
|---|---|
| ✅ 真机实测 | 读取整个桌面（一台真机：10 页、54 个应用、18 个文件夹）；用 adb 注入手势拖动，合成新文件夹、放进已有文件夹都成功 |
| 🟡 写完，未联调 | App 自己的手势执行（同一组参数）、跨页拖动、新建文件夹自动改名、进度条与音量键中止 |
| ⬜ 还没做 | 适配 ColorOS 以外的桌面；第一个可安装的版本 |

验证设备：一加 13 · Android 16 · ColorOS 桌面。其他桌面（原生、小米、华为等）的节点结构不同，需要逐个适配，欢迎贡献。

## 它怎么工作

1. **读桌面**：无障碍服务读出每一页的应用和文件夹（只读）
2. **归类**：包名词典 → 应用名词典 → 包名/应用名关键词 → 系统分类 → 其他；认不出的、只有一个应用的类别留在原处
3. **执行**：长按 → 拖到目标图案正中心 → 悬停 → 松手，逐个放进文件夹；每一步都重新读一遍桌面确认放对了再继续

ColorOS 上原本想用的语义入口（「移动项目」→「创建文件夹」）走不通：图标能拎起来，但拎起后桌面节点变空、没有落点，所以改用模拟手势。细节和实测参数见 [决策记录](docs/03-决策记录.md)。

## 构建

JDK 17 + Android SDK 35：

```bash
./gradlew assembleDebug                 # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # 分类器与规划器的单元测试
```

零第三方运行时依赖（只用 Android 框架 API），测试依赖不进 APK。真机测试步骤见 [真机测试指南](docs/05-真机测试指南.md)。

## 文档

- [产品设计](docs/01-产品设计.md) · [技术方案](docs/02-技术方案.md) · [决策记录](docs/03-决策记录.md) · [路线图与验收](docs/04-路线图与验收.md) · [真机测试指南](docs/05-真机测试指南.md)
- [交互原型](design/一键理桌原型-v1.html)（浏览器直接打开）

## 协议

[MIT](LICENSE)
