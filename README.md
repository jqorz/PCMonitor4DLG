# PCMonitor4DLG

基于 Kotlin Multiplatform (Compose for Desktop) 的 PC 性能监控工具，通过蓝牙将主机硬件状态实时同步至 DA14585 蓝牙时钟设备（DLG 系列）显示。

## 功能介绍

### 实时系统监控

应用主界面实时展示以下主机状态信息（刷新间隔 1 秒）：

- **CPU 占用率**
- **内存占用率**
- **GPU 温度**
- **GPU 占用率**
- **网络上行 / 下行速率**（自动适配 B/s、KB/s、MB/s、GB/s）

### 蓝牙设备连接

- 启动后自动最小化到系统托盘运行
- 点击底部蓝牙状态栏可打开设备选择弹窗
- 弹窗支持扫描 BLE 设备，可勾选"只显示 DLG 开头的设备"过滤目标设备
- 连接成功后自动开始数据同步（每秒一次）

### 启动时自动连接设备

在设置中开启"启动时自动连接设备"后：

- 应用启动时自动尝试连接上一次成功连接的蓝牙设备
- 连接失败时每隔 30 秒自动重试，直到连接成功
- 关闭该开关后立即停止自动重连

### 系统托盘

- 最小化后应用在系统托盘运行，不占用任务栏
- 托盘图标支持双击恢复窗口、右键菜单退出

### 退出行为

点击窗口关闭按钮时弹出退出确认弹窗，提供两个选项：

- **最小化到托盘**：窗口隐藏，应用继续在后台运行
- **退出应用**：彻底关闭应用进程

弹窗可勾选"不再询问，记住我的选择"。

## 环境要求

- Windows 10/11
- Python 3.x（需安装 `bleak` 库，用于 BLE 通信）
- 蓝牙适配器（支持 BLE 4.0+）

## 安装与运行

### 安装 Python 依赖

```bash
pip install bleak
```

### 运行应用

```bash
./gradlew :desktopApp:run
```

### 打包

```bash
./gradlew :desktopApp:packageDistribution
```

## 项目结构

```
├── desktopApp/          # 桌面应用入口
│   └── src/main/kotlin/ # main.kt、窗口与托盘管理
├── shared/              # 共享代码
│   ├── src/commonMain/  # 跨平台代码（UI、ViewModel 接口、数据模型）
│   └── src/jvmMain/     # JVM 平台实现（BLE 管理、系统监控、设置存储）
└── resources/           # 资源文件（图标、ble_service.py）
```

## 技术栈

- Kotlin Multiplatform + Compose Multiplatform (Desktop JVM)
- BLE 通信：Python `bleak` 库（通过子进程调用）
- 系统监控：JNA / OSHI
- 设置持久化：`java.util.prefs.Preferences`
- 开机自启：Windows 注册表写入
