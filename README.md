# 双板滑雪 IMU 训练面板

这个项目用两个 ESP32 + 六轴 IMU 节点采集左右雪板/雪鞋动作，并通过 Web Bluetooth 在 `index.html` 中实时展示：

- 左右立刃角
- 左右同步分
- 左右承重估计
- 左右脚掌承重点估计
- 换刃次数
- 当前阶段：静止、平板、左刃、右刃、换刃
- 双雪板 3D 姿态
- 左右立刃角历史曲线
- JSONL 数据记录导出

## 硬件建议

每个节点包含：

- ESP32-C3 / ESP32-C5 开发板
- ICM-42670-P 六轴 IMU
- 可选 SHTC3 温湿度传感器
- 电池供电模块

安装位置建议：

- 想判断立刃、换刃、双板平行：优先固定在左右雪板上。
- 想判断脚踝动作：固定在左右雪鞋上。
- 想判断核心发力/上身稳定：建议后续增加腰部或胸口 IMU。

## Arduino 端烧录

打开 `esp32_c5_data_reader.ino`。

左板烧录前保持：

```cpp
#define SKI_SENSOR_SIDE "L"
```

右板烧录前改成：

```cpp
#define SKI_SENSOR_SIDE "R"
```

然后分别烧录到两块 ESP32。

如果安装方向导致角度符号反了，可以改：

```cpp
#define SKI_EDGE_SIGN 1.0f
#define SKI_PITCH_SIGN 1.0f
```

调试方法：

1. 左右板平放后点击网页“静止校准”。
2. 用手把两块板向同一方向侧倾。
3. 如果某一块板的曲线方向和另一块相反，就把那一块固件里的 `SKI_EDGE_SIGN` 改成 `-1.0f` 后重新烧录。

### 依赖库

在 Arduino IDE 的库管理器中安装：

- `Adafruit SHTC3`
- `ICM42670P`
- `NimBLE-Arduino`

### I2C 引脚

当前代码使用：

```cpp
#define I2C_SDA 7
#define I2C_SCL 8
```

如果你的开发板实际接线是 GPIO8/GPIO9，只需要改这里：

```cpp
#define I2C_SDA 8
#define I2C_SCL 9
```

## 校准流程

校准分两步。

第一步是上电自动陀螺仪零偏校准：

1. 给 ESP32 上电。
2. 保持节点静止约 2 秒。
3. 串口会输出 `gyro_bias_x/y/z`。

第二步是网页静止姿态校准：

1. 打开 `index.html`。
2. 分别点击“连接左板”“连接右板”。
3. 把左右雪板/雪鞋平放、平行、静止。
4. 点击“静止校准”。
5. 网页显示“已校准”后，当前姿态会被作为 `0°` 立刃角基准。

这一步很重要。否则传感器粘贴角度、绑带歪斜、雪板平放误差都会直接体现在立刃角里。

## 打开网页

Web Bluetooth 通常要求安全上下文。推荐用本地 HTTP 服务打开：

```bash
python3 -m http.server 8000
```

然后在 Chrome 中访问：

```text
http://localhost:8000/index.html
```

实时采集页面：

- `index.html`：连接左右 ESP32，实时显示动作和导出日志。

轨迹回放页面：

- `replay.html`：加载 JSONL 日志，回放左右立刃角、动作阶段、同步分和估算滑行路径。
- 默认会尝试加载 `ski_imu_log_1777385783530.jsonl`。
- 也可以把任意 JSONL 日志拖到页面左侧区域，或通过文件选择器打开。

## Android 手机客户端

仓库里新增了 `android/` Android 工程，用 WebView 复用 `index.html` / `replay.html` 的界面和算法，并用原生 BLE 替代 Web Bluetooth：

- 采集页点击“连接左板 / 连接右板”会扫描 `SKI-IMU-L` / `SKI-IMU-R` 设备。
- 点击“开始记录”后，每条 BLE 数据都会立刻追加保存到 App 私有目录的 JSONL 文件。
- 点击“回放日志”可以直接加载本机最新日志，不需要先导出再导入。
- 点击“导出日志”会把当前 JSONL 复制到手机 `Downloads/`。

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后运行到手机即可。构建时会自动把仓库根目录的 `index.html` 和 `replay.html` 复制到 App assets。

命令行构建：

```bash
cd android
gradle assembleDebug
```

需要 Android SDK、Android Gradle Plugin 和一台支持 BLE 的 Android 手机。首次运行要允许蓝牙权限；Android 11 及以下还需要位置权限才能扫描 BLE。

## 数据格式

ESP32 串口会输出完整 JSON，方便调参和离线分析；BLE 会发送精简 JSON，避免 Web Bluetooth 通知包过长。

### 串口完整 JSON 示例

```json
{
  "id": "L",
  "t": 12.34,
  "tp": 24.8,
  "h": 41.2,
  "ax": 0.01,
  "ay": 0.02,
  "az": 1.00,
  "gx": 0.4,
  "gy": 0.1,
  "gz": -0.2,
  "am": 1.002,
  "gm": 0.6,
  "r": 0.012,
  "p": -0.006,
  "y": 0.040,
  "ed": 8.4,
  "pd": -1.2,
  "yd": 2.3,
  "rr": 36.5,
  "ph": "EDGE_CHANGE",
  "ec": 3,
  "cal": 1,
  "imu": 1
}
```

### BLE 精简 JSON 示例

```json
{
  "id": "L",
  "t": 12.3,
  "tp": 24.8,
  "ed": 8.4,
  "pd": -1.2,
  "rr": 36.5,
  "pr": -12.0,
  "am": 1.04,
  "gm": 38.1,
  "ph": "EDGE_CHANGE",
  "ec": 3,
  "cal": 1,
  "imu": 1
}
```

字段含义：

- `id`：节点身份，`L` 左板，`R` 右板
- `ed`：相对静止校准零点的立刃角，单位度
- `pd`：相对零点的俯仰角，单位度
- `yd`：短期航向角，单位度，六轴 IMU 会漂移，只作参考
- `rr`：Roll 角速度，单位度/秒
- `pr`：Pitch 角速度，单位度/秒，用于辅助判断脚掌前后承重点可信度
- `am`：加速度模长，单位 g，用于辅助估算承重趋势
- `ph`：单板阶段，`STILL`、`FLAT`、`LEFT_EDGE`、`RIGHT_EDGE`、`EDGE_CHANGE`
- `ec`：换刃次数
- `cal`：是否完成静止姿态校准
- `imu`：IMU 是否初始化成功

## 当前能力和限制

两个六轴 IMU 可以较可靠地判断：

- 立刃角
- 换刃时机
- 左右板是否同步
- 哪只脚更可能承重，以及左右承重转移趋势
- 脚掌承重点大概偏前掌/脚心/后跟，以及偏左侧刃/右侧刃
- 是否长时间平板滑
- 左右板角度明显分离

两个六轴 IMU 不能直接准确判断：

- 人体核心是否真正发力
- 精确重心位置
- 长时间准确航向角
- 准确速度
- 真实脚底压力或精确公斤力
- 真实足底压力中心

如果要继续做“核心发力、屈膝折叠、上身是否带转、重心横移”，建议增加第三个 IMU 到腰部或胸口。

页面里的“承重估计”是由立刃角、加速度模长、Roll 角速度和外板经验规则推算出的趋势值，不等同于压力传感器测得的真实压力。
页面里的“脚掌承重点估计”主要由 Pitch 角、立刃角和角速度推算，只适合观察趋势，不适合做精确鞋垫压力分析。
