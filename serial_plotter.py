#!/usr/bin/env python3
"""
ESP32-C5 串口数据读取与实时可视化
依赖: pip install pyserial matplotlib numpy
运行: python serial_plotter.py [串口名称]
示例: python serial_plotter.py COM3
"""

import sys
import json
import serial
import matplotlib.pyplot as plt
import matplotlib.animation as animation
import numpy as np

# 修复matplotlib中文显示问题
plt.rcParams['font.sans-serif'] = ['SimHei', 'Microsoft YaHei', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# 配置参数
MAX_POINTS = 200  # 显示最多数据点
BAUD_RATE = 115200

# 数据缓存
time_data = []
adc1_data = []
adc2_data = []
temp_data = []

# 串口对象
ser = None

# 获取串口名称
if len(sys.argv) > 1:
    PORT = sys.argv[1]
else:
    print("请指定串口名称！用法示例:\n")
    print("  python serial_plotter.py COM3\n")
    print("可用串口列表:")
    import serial.tools.list_ports
    ports = list(serial.tools.list_ports.comports())
    if ports:
        for port in ports:
            print(f"  {port.device} - {port.description}")
    else:
        print("  没有找到可用串口，请检查设备连接")
    sys.exit(1)

def init_serial():
    """初始化串口"""
    global ser
    try:
        ser = serial.Serial(PORT, BAUD_RATE, timeout=1)
        print(f"已连接 {PORT} @ {BAUD_RATE} baud")
        return True
    except Exception as e:
        print(f"无法打开串口 {PORT}: {e}")
        return False

def read_serial_data():
    """读取一行串口数据并解析"""
    if ser is None or not ser.is_open:
        return None

    try:
        line = ser.readline().decode('utf-8').strip()
        if not line:
            return None
        if line.startswith("{") and line.endswith("}"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                return None
        return None
    except Exception as e:
        print(f"读取错误: {e}")
        return None

def update_plot(frame):
    """更新绘图（动画回调）"""
    # 读取数据
    data = read_serial_data()
    if data is not None and "time" in data:
        time_val = data["time"] / 1000.0  # 转换为秒
        time_data.append(time_val)
        adc1_data.append(data["adc1_volt"])
        adc2_data.append(data["adc2_volt"])
        if "internal_temp" in data:
            temp_data.append(data["internal_temp"])
        else:
            temp_data.append(np.nan)

        # 限制数据点数量
        if len(time_data) > MAX_POINTS:
            time_data.pop(0)
            adc1_data.pop(0)
            adc2_data.pop(0)
            temp_data.pop(0)

    # 更新曲线数据
    if len(time_data) > 0:
        line_adc1.set_data(time_data, adc1_data)
        line_adc2.set_data(time_data, adc2_data)
        if any(not np.isnan(t) for t in temp_data):
            line_temp.set_data(time_data, temp_data)

        # 自动调整x轴范围
        ax.relim()
        ax.autoscale_view()

    return [line_adc1, line_adc2, line_temp]

# 初始化绘图
fig, ax = plt.subplots(figsize=(10, 6), dpi=100)
line_adc1, = ax.plot([], [], 'r-', label='ADC1 (V)', linewidth=1.5)
line_adc2, = ax.plot([], [], 'b-', label='ADC2 (V)', linewidth=1.5)
line_temp, = ax.plot([], [], 'g-', label='内部温度 (°C)', linewidth=1.5)

ax.set_xlabel('时间 (秒)')
ax.set_ylabel('数值')
ax.set_title('ESP32-C5 实时数据可视化')
ax.grid(True, alpha=0.3)
ax.legend(loc='upper right')

# 初始化串口
if not init_serial():
    sys.exit(1)

# 创建动画
ani = animation.FuncAnimation(
    fig, update_plot,
    interval=50,  # 更新间隔(毫秒)
    blit=True,
    cache_frame_data=False
)

plt.tight_layout()
print("开始绘图，关闭绘图窗口退出...")
plt.show()

# 关闭串口
if ser is not None and ser.is_open:
    ser.close()
print("串口已关闭")
