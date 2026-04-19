#!/usr/bin/env python3
import sys
import serial
import serial.tools.list_ports

print("=" * 50)
print("ESP32-C5 串口测试工具 (只读取不绘图)")
print("=" * 50)
print()

# 列出所有串口
print("可用串口:")
ports = list(serial.tools.list_ports.comports())
if ports:
    for p in ports:
        print(f"  {p.device:10} - {p.description}")
else:
    print("  无可用串口")
print()

if len(sys.argv) < 2:
    print("请指定串口: python simple_read.py COMx")
    sys.exit(1)

port_name = sys.argv[1]
print(f"尝试打开: {port_name}  @ 115200 ...")

try:
    ser = serial.Serial(port_name, 115200, timeout=2)
    print(f"✓ 串口打开成功！")
    print()
    print("等待数据... (按 Ctrl+C 退出)\n")

    count = 0
    while True:
        line = ser.readline()
        if not line:
            continue
        try:
            text = line.decode('utf-8').strip()
            if text:
                print(f"  {text}")
                count += 1
        except Exception as e:
            print(f"解码错误: {line}")
except KeyboardInterrupt:
    print()
    print(f"接收了 {count} 行数据")
    print("退出")
except Exception as e:
    print(f"✗ 错误: {type(e).__name__}: {e}")
    print()
    input("按回车退出...")
finally:
    if 'ser' in locals() and ser.is_open:
        ser.close()
        print("串口已关闭")
