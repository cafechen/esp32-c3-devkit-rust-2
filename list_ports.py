#!/usr/bin/env python3
import serial.tools.list_ports

ports = list(serial.tools.list_ports.comports())
if not ports:
    print("没有找到可用串口")
else:
    print("可用串口列表:")
    for port in ports:
        print(f"  {port.device}: {port.description}")
