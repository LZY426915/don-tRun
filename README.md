# 东西不跑

<p align="center">
  <img src="./app/src/main/res/drawable-nodpi/logo_mark_adaptive.png" alt="东西不跑应用图标" width="28%" />
</p>

<p align="center">
  <strong>小东西帮你记住每一件物品</strong>
</p>

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Android" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" /></a>
  <a href="#"><img src="https://img.shields.io/badge/minSdk-26-6A1B9A" alt="minSdk 26" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-22c55e" alt="License MIT" /></a>
</p>

“东西不跑”是一款面向家庭与宿舍场景的 Android 物品管理应用。它帮助用户记录物品放在哪里、还有多少、何时过期以及当前状态，并通过“小东西”智能体提供自然语言查询与管理能力。

本项目在开源项目框架基础上继续开发，完成了品牌与紫色视觉体系升级，并扩展了智能体、真实 AI 调用、天气查询、语音输入、图片理解、数据备份等能力。项目继续遵循并保留原项目的 MIT License。

---

## 当前功能

### 物品管理

- 拍照或跳过拍照快速录入物品。
- 记录名称、图片、分类、存放位置、数量、有效期、价格和备注。
- 查看物品详情，支持编辑、标记已用完、评价和删除。
- 在首页按分类筛选最近添加的物品。
- 在库房中搜索物品名称、分类、位置或备注。
- 按全部、已用完、待评价、已评价等状态查看物品。

### 分类、位置与生命周期

- 按分类或存放位置两个维度浏览物品。
- 支持新增、编辑和删除分类。
- 支持创建多级存放位置，例如“宿舍 -> 衣柜 -> 下层”。
- 删除物品后先进入回收站，30 天内可恢复。
- 支持回收站批量恢复和永久删除。
- 物品标记为已用完后，可进行星级评价并填写评价内容。

### 到期提醒

- 使用 WorkManager 约每 12 小时检查一次物品有效期。
- 对未来 7 天内到期或已经过期的物品发送本地通知。
- 对同一物品的相同提醒状态进行当天去重。
- 可在“我的 -> 到期提醒”中集中查看需要关注的物品。

### “小东西”智能体

- 支持文字对话、语音输入、拍照和相册图片。
- 可查询物品、分类、位置、库存状态和即将过期物品。
- 可通过工具调用修改真实物品数据，并对高风险操作进行确认。
- 支持图片理解与物品录入辅助。
- 支持结合高德天气回答天气、出行和生活建议类问题。
- 保存和搜索历史对话。

### 设置与数据

- 在“我的 -> API-Key 管理系统”中管理文字搜索、图片识别和天气服务配置。
- 导出 ZIP 数据备份，并从 ZIP 备份恢复数据。
- 检查新版本、下载并调起安装。
- 使用 Room 在设备本地保存物品与配置数据。

---

## 当前视觉

- 品牌主色为南大紫：`#6A1B9A` 到 `#9C27B0`。
- 当前图标为紫色立体收纳盒与房屋，表达“物品有处可寻”。
- 首页使用紫色渐变沉浸式顶部区域，并以“小东西”作为智能入口。
- 主体使用淡紫白背景、白色大圆角卡片和紫色选中状态。
- 底部使用毛玻璃悬浮导航，中间拍照按钮为主要操作入口。

---

## 页面入口

| 页面 | 主要能力 |
| --- | --- |
| 首页 | 小东西入口、即将过期、全部物品、最近添加、分类筛选 |
| 分类 | 按分类或位置浏览，管理分类与多级位置 |
| 拍照 | 拍摄多张图片并进入物品录入 |
| 库房 | 搜索物品，按状态筛选、编辑、用完、评价和删除 |
| 我的 | 数据统计、API-Key 管理、到期提醒、回收站和设置 |
| 小东西 | 文字、语音、图片对话与物品管理工具 |

---

## 技术栈

- Kotlin 2.1
- Jetpack Compose + Material 3
- Navigation Compose
- MVVM
- Room
- Hilt
- WorkManager
- CameraX
- Coil
- Kotlin Coroutines
- Kotlin Serialization
- OkHttp
- Haze 毛玻璃效果

架构主链路：

```text
Compose UI -> ViewModel -> Repository / Agent Tool -> Room DB / External API
```

---

## 项目结构

```text
app/src/main/java/com/youshu/app/
├─ data/
│  ├─ agent/          # 小东西智能体、库存工具、天气工具与对话历史
│  ├─ ai/             # 文字、图片与语音推理调用
│  ├─ backup/         # ZIP 数据备份与恢复
│  ├─ local/          # Room 实体、DAO、数据库与迁移
│  ├─ repository/     # 数据仓库层
│  └─ update/         # 版本检查、下载与安装
├─ di/                # Hilt 依赖注入
├─ ui/
│  ├─ components/     # 通用 UI 与毛玻璃组件
│  ├─ navigation/     # 路由与底部导航
│  ├─ screen/         # 页面实现
│  ├─ theme/          # 紫色视觉主题
│  └─ viewmodel/      # 页面状态与交互逻辑
├─ util/              # 到期通知、日期、图片和录音工具
├─ MainActivity.kt
└─ YouShuApplication.kt
```

---

## 构建与安装

### 环境要求

- Android Studio 最新稳定版
- JDK 17
- Android SDK 35
- Android 8.0（API 26）及以上运行设备

### 构建 Debug APK

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

---

## 首次体验路径

1. 进入“分类”，创建常用分类与存放位置。
2. 点击底部中央拍照按钮，录入第一件物品。
3. 进入“库房”，尝试搜索、标记已用完和评价。
4. 进入“我的 -> 到期提醒 / 回收站”，查看生命周期管理能力。
5. 从首页进入“小东西”，尝试询问“我的充电器放在哪里？”或“有哪些东西快过期了？”。

---

## API-Key 配置

进入“我的 -> API-Key 管理系统”，可编辑或新增不同用途的服务配置。

| 用途 | 当前预置服务 | 主要能力 |
| --- | --- | --- |
| 文字搜索 | DeepSeek | 小东西对话、物品查询和管理工具调用 |
| 图片识别 | 通义千问 Qwen | 图片理解与物品录入辅助 |
| 天气服务 | 高德天气 | 天气查询与生活建议 |

每项配置包含模型别名、模型来源、接口地址、模型名称和 API Key。API Key 属于敏感凭据，请勿提交到公开仓库或分享至公开材料。

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 相机 | 拍照录入、智能体拍照发送图片 |
| 麦克风 | 向小东西进行语音提问 |
| 照片 / 媒体 | 从相册选择图片 |
| 通知 | 展示物品到期提醒 |
| 定位 | 为天气查询提供位置 |
| 网络 | 智能体、图片识别、天气和检查更新 |

---

## 开源与致谢

本项目基于原开源项目继续开发，并保留其 MIT License。感谢原作者和所有开源依赖的贡献者。

问题反馈 / 讨论：[Linux.do 社区](https://linux.do/)

## License

[MIT](./LICENSE)
