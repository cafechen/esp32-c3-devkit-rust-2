#!/usr/bin/env python3
"""
ESP32-C5 SHTC3 + ICM-42670-P 实时可视化
显示: 温度/湿度 + 加速度 + 陀螺仪
"""

import sys
import json
import serial
import matplotlib.pyplot as plt
import matplotlib.animation as animation
import numpy as np
import serial.tools.list_ports

# 修复matplotlib中文显示问题
plt.rcParams['font.sans-serif'] = ['SimHei', 'Microsoft YaHei', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# 配置参数
MAX_POINTS = 150
BAUD_RATE = 115200

# 数据缓存
time_data = []
# 温湿度
temp_data = []
hum_data = []
# 加速度 (g)
ax_data = []
ay_data = []
az_data = []
# 陀螺仪 (dps)
gx_data = []
gy_data = []
gz_data = []

# 获取串口名称
if len(sys.argv) > 1:
    PORT = sys.argv[1]
else:
    print("请指定串口名称！用法示例:\n")
    print("  python sensor_plotter.py COM3\n")
    print("可用串口列表:")
    ports = list(serial.tools.list_ports.comports())
    if ports:
        for port in ports:
            print(f"  {port.device} - {port.description}")
    else:
        print("  没有找到可用串口，请检查设备连接")
    sys.exit(1)

try:
    ser = serial.Serial(PORT, BAUD_RATE, timeout=1)
    print(f"已连接 {PORT} @ {BAUD_RATE} baud")
except Exception as e:
    print(f"无法打开串口 {PORT}: {e}")
    print()
    input("按回车键退出...")
    sys.exit(1)

def read_serial_data():
    """读取一行串口数据并解析"""
    try:
        line = ser.readline().decode('utf-8').strip()
        if not line:
            return None
        # 打印调试信息，看看收到了什么
        # print(f"RX: {line}")
        if line.startswith("{") and line.endswith("}":
            try:
                data = json.loads(line)
                print(f"✓ 收到数据: {data}")
                return data
            except json.JSONDecodeError as e:
                print(f"✗ JSON解析错误: {line}, {e}")
                return None
        else:
            # 打印非JSON数据，帮助调试
            print(f"  调试输出: {line}")
            return None
    except Exception as e:
        print(f"✗ 读取错误: {e}")
        return None

def update_plot(frame):
    """更新绘图"""
    data = read_serial_data()
    if data is not None and "time" in data:
        time_val = data["time"]
        time_data.append(time_val)

        # 温湿度
        temp_data.append(data.get("temp", np.nan))
        hum_data.append(data.get("humidity", np.nan))

        # 加速度
        ax_data.append(data.get("ax", np.nan))
        ay_data.append(data.get("ay", np.nan))
        az_data.append(data.get("az", np.nan))

        # 陀螺仪
        gx_data.append(data.get("gx", np.nan))
        gy_data.append(data.get("gy", np.nan))
        gz_data.append(data.get("gz", np.nan))

        # 限制数据点数量
        if len(time_data) > MAX_POINTS:
            time_data.pop(0)
            temp_data.pop(0)
            hum_data.pop(0)
            ax_data.pop(0)
            ay_data.pop(0)
            az_data.pop(0)
            gx_data.pop(0)
            gy_data.pop(0)
            gz_data.pop(0)

    # 更新曲线
    if len(time_data) > 0:
        line_temp.set_data(time_data, temp_data)
        line_hum.set_data(time_data, hum_data)
        line_ax.set_data(time_data, ax_data)
        line_ay.set_data(time_data, ay_data)
        line_az.set_data(time_data, az_data)
        line_gx.set_data(time_data, gx_data)
        line_gy.set_data(time_data, gy_data)
        line_gz.set_data(time_data, gz_data)

        # 更新轴范围
        for ax in axes:
            ax.relim()
            ax.autoscale_view()

    return [line_temp, line_hum, line_ax, line_ay, line_az, line_gx, line_gy, line_gz]

# 创建三行子图
fig, axes = plt.subplots(3, 1, figsize=(10, 9), dpi=100)

# 1. 温湿度
ax0 = axes[0]
line_temp, = ax0.plot([], [], 'r-', label='温度 (°C)', linewidth=1.5)
line_hum, = ax0.plot([], [], 'b-', label='湿度 (%RH)', linewidth=1.5)
ax0.set_ylabel('温湿度')
ax0.grid(True, alpha=0.3)
ax0.legend(loc='upper right')
ax0.set_title('ESP32-C5 板载传感器实时数据', fontweight='bold')

# 2. 加速度
ax1 = axes[1]
line_ax, = ax1.plot([], [], 'r-', label='X (g)', linewidth=1.2)
line_ay, = ax1.plot([], [], 'g-', label='Y (g)', linewidth=1.2)
line_az, = ax1.plot([], [], 'b-', label='Z (g)', linewidth=1.2)
ax1.set_ylabel('加速度')
ax1.grid(True, alpha=0.3)
ax1.legend(loc='upper right')

# 3. 陀螺仪
ax2 = axes[2]
line_gx, = ax2.plot([], [], 'r-', label='X (°/s)', linewidth=1.2)
line_gy, = ax2.plot([], [], 'g-', label='Y (°/s)', linewidth=1.2)
line_gz, = ax2.plot([], [], 'b-', label='Z (°/s)', linewidth=1.2)
ax2.set_ylabel('陀螺仪')
ax2.set_xlabel('时间 (秒)')
ax2.grid(True, alpha=0.3)
ax2.legend(loc='upper right')

plt.tight_layout()

# 创建动画
ani = animation.FuncAnimation(
    fig, update_plot,
    interval=50,
    blit=True,
    cache_frame_data=False
)

print("开始绘图，关闭绘图窗口退出...")
try:
    plt.show()
except Exception as e:
    print(f"绘图错误: {e}")
    input("按回车退出...")

# 关闭串口
if ser.is_open:
    ser.close()
print("串口已关闭")
