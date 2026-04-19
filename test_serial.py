#!/usr/bin/env python3
import sys
import serial
import serial.tools.list_ports

print("Python版本:", sys.version)
print()
print("列出所有串口:")
ports = list(serial.tools.list_ports.comports())
if ports:
    for port in ports:
        print(f"  {port.device} - {port.description}")
else:
    print("  没有找到串口")
print()

if len(sys.argv) > 1:
    PORT = sys.argv[1]
    print(f"尝试打开 {PORT}...")
    try:
        ser = serial.Serial(PORT, 115200, timeout=1)
        print(f"✓ 成功打开 {PORT}")
        print("等待数据... (按Ctrl+C退出)")
        print()
        while True:
            line = ser.readline().decode('utf-8', errors='replace').strip()
            if line:
                print(line)
    except KeyboardInterrupt:
        print("\n退出")
    except Exception as e:
        print(f"✗ 错误: {e}")
    finally:
        if 'ser' in locals() and ser.is_open:
            ser.close()
            print("串口已关闭")
