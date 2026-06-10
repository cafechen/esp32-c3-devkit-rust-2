# 三 IMU 滑雪动作识别设计

## 目标

第一版支持 2 个或 3 个 IMU。当前最小可用配置是左右小腿两个 IMU，腰部 / 骶骨 IMU 是增强项：

- `L`：左小腿 IMU，设备名 `SKI-IMU-L`
- `R`：右小腿 IMU，设备名 `SKI-IMU-R`
- `W`：腰部 / 骶骨 IMU，设备名 `SKI-IMU-W`，可选

传感器端负责采集、陀螺仪零偏、基础姿态和 BLE 传输。手机端负责多传感器融合、实时规则指标、日志保存、后续导出。电脑端以后读取同一份 JSONL 做回测、标注和训练。

## 传感器端数据包

BLE 使用紧凑 CSV，避免高频 JSON 过长：

```text
S,id,seq,ms,ax_mg,ay_mg,az_mg,gx_dps10,gy_dps10,gz_dps10,roll_cd,pitch_cd,yaw_cd,cal
```

示例：

```text
S,W,1024,12345,12,-8,1003,4,-2,1,35,-12,80,1
```

含义：

- `id`：`W/L/R`
- `seq`：传感器递增序号
- `ms`：传感器上电后的毫秒时间
- `ax_mg/ay_mg/az_mg`：加速度，单位 `0.001g`
- `gx_dps10/gy_dps10/gz_dps10`：角速度，单位 `0.1 deg/s`
- `roll_cd/pitch_cd/yaw_cd`：基础姿态，单位 `0.01 deg`
- `cal`：是否完成静止姿态校准

固件默认 100Hz 采样和发送。若三路 BLE 在雪场不稳定，可将 `SAMPLE_INTERVAL_MS` 从 `10` 改为 `20`，即 50Hz。

## 手机端实时计算

`index.html` 在 Android WebView 中运行，原生 `MainActivity` 提供三路 BLE 桥接和日志文件写入。只连接 `L/R` 时进入双腿模式；再连接 `W` 后进入三节点模式。

Android App 会在页面加载完成和蓝牙权限通过后自动扫描并连接 `SKI-IMU-L`、`SKI-IMU-R`、`SKI-IMU-W`，意外断开后也会自动重试。浏览器 Web Bluetooth 受平台限制，不能无点击自动连接，只能在 Android App 中实现。

实时计算频率：

- 原始数据：按 BLE 到达频率保存
- 特征窗口：每 0.5 秒计算一次
- UI：每 0.5 秒刷新核心判断，3D 和曲线用动画帧平滑显示

当前实现的第一版指标：

- 转腿一致性：左右小腿 `gz` 峰值、积分角度、启动时机、持续时间，只需要 `L/R`
- 后坐风险：左右小腿前倾、腰部 pitch、躯干-胫骨夹角、姿态稳定性，需要 `W/L/R`
- 重心转换：腰部 roll 变化、横向加速度、腿腰相位差、转换平顺度，需要 `W/L/R`
- 左右承重趋势：腰部侧倾、横向加速度、左右腿稳定性、左右腿转动强度，需要 `W/L/R`

缺少腰部 IMU 时，依赖腰部的指标显示为 `--`，并保留原始 `L/R` 数据用于后续回测。

这些都是规则算法。模型推理后续应放在同一套特征窗口之后，先用教练标注数据训练。

## 日志格式

Android 内部记录仍然使用 JSONL，每行一个 JSON 对象。开始记录前可以输入记录名称，App 会用开始时间和记录名称生成文件名，并在 `session_meta` 中保存名称、时间戳和时间基准。

```json
{"kind":"session_meta","hostTs":1770000000000,"startedAt":1770000000000,"sessionName":"滑雪训练记录","schema":"ski_imu_jsonl_v0","timebase":{"hostTs":"phone_receive_unix_ms","sessionElapsedMs":"hostTs - startedAt","sensorMs":"device_uptime_ms"}}
{"kind":"raw","hostTs":1770000000100,"sessionElapsedMs":100,"id":"W","sensorMs":24100,"ax":0.01,"ay":-0.02,"az":1.00,"gx":0.3,"gy":-0.1,"gz":0.0,"roll":0.4,"pitch":-0.1,"yaw":0.8}
{"kind":"features","hostTs":1770000000500,"sessionElapsedMs":500,"algorithmVersion":"phone-rules-0.1.0","posture":{},"rotation":{},"com":{},"load":{}}
{"kind":"calibration","hostTs":1770000000800,"sessionElapsedMs":800,"command":"static","status":"sent","connectedSensors":["L","R"]}
```

后续电脑端回测时，应优先使用 `kind=raw` 重新计算姿态、窗口和特征；`kind=features` 只作为当时手机端算法版本的结果快照。

导出到 Downloads 时，App 会把内部 JSONL 拆成训练 ZIP 包，包内固定包含：

- `metadata.json`：记录名称、开始时间、导出时间、文件清单、计数、传感器摘要、时间基准、同步标记
- `raw_imu.jsonl`：只包含 `kind=raw` 的原始 IMU 数据，保留 `ax/ay/az/gx/gy/gz/roll/pitch/yaw`
- `calibration.json`：静止校准指令事件、每个传感器的 `cal` 状态和首次校准时间
- `features.jsonl`：手机端当时计算出的规则特征快照
- `labels.json`：空标注文件，占位给电脑端回放标注工具写入动作窗口
- `source_log.jsonl`：原始内部日志备份，便于兼容旧回放和排查问题

滑雪现场不要求学员操作手机。采集端只负责连接、校准、记录；动作标签由教练在 `replay.html` 回放时补充。教练可以加载视频和日志，拖动统一时间轴，设置阶段起点和终点，选择 `TRANSITION_55`、`RIGHT_UPPER`、`RIGHT_LOWER`、`LEFT_UPPER`、`LEFT_LOWER` 五个状态/阶段，然后导出 `labels.json`。导出的 `ski_imu_stage_labels_v1` 会保留兼容训练脚本的 `action/quality/errorType` 字段，并额外写入阶段名、物理含义、状态条件、下一状态条件、必看指标和辅助指标。

App 内的记录管理支持：

- 查看多条历史记录
- 显示记录名称、开始时间、文件大小、粗略 raw/features 数量
- 重命名记录
- 删除记录
- 导出当前记录或选中的历史记录为训练 ZIP 包到 Downloads

## 后续训练闭环

建议的数据闭环：

1. 手机采集并导出 JSONL
2. 视频和 JSONL 按时间对齐
3. 标注动作窗口、质量、错误类型、教练备注
4. 电脑端用原始数据重算特征
5. 用 `features + labels` 训练轻量模型
6. 将稳定模型导出到 Android 端做低频推理

不要只保存评分。算法早期阈值会频繁变化，保留原始 IMU 才能回测和重训。
