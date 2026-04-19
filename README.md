# ESP32-C5 数据读取与可视化

## 板载SHTC3 + ICM-42670-P版本 (推荐)

如果你用的是Espressif官方开发板，带板载温湿度+IMU传感器：

### 1. Arduino端设置

1. 在 Arduino IDE 中打开 `esp32_c5_sensors.ino`
2. 开发板选择 **ESP32C5 DevKitM-1**
3. **先安装库**: 工具 → 管理库 → 搜索安装:
   - `SHTC3` by Sensirion
   - `SparkFun ICM-42670-P` by SparkFun
4. 编译并烧录到ESP32-C5

### 2. 运行Python可视化

```bash
python sensor_plotter.py COMx
```

将 `COMx` 替换为你的实际串口号，例如 `COM3`。

输出包含：
- 上: 温度 (红色) + 湿度 (蓝色)
- 中: X/Y/Z 加速度 (单位: g)
- 下: X/Y/Z 陀螺仪 (单位: °/秒)

---

## 通用ADC版本

读取外接模拟传感器：`esp32_c5_data_reader.ino` + `serial_plotter.py`

读取:
- **ADC1**: GPIO1 (ADC1_CH0) 0-3.3V电压
- **ADC2**: GPIO2 (ADC1_CH1) 0-3.3V电压
- **内部温度**: ESP32-C5芯片内置温度传感器

## I2C 板载传感器引脚

SHTC3 和 ICM-42670-P 都连接到：
- SDA: **GPIO8**
- SCL: **GPIO9**

已经在代码中定义，不需要修改。

## 安装Python依赖

```bash
pip install pyserial matplotlib numpy
```

## 常见问题

- **找不到串口**: 检查驱动是否安装，CH340/CP2102驱动
- **数据乱码**: 确认Arduino代码和Python的波特率都是 115200
- **传感器初始化失败**: 检查库是否正确安装
