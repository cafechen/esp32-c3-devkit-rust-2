import json
import time
import serial
from collections import deque

import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation

SERIAL_PORT = "COM3"
BAUDRATE = 115200
MAX_POINTS = 200

ser = serial.Serial(SERIAL_PORT, BAUDRATE, timeout=1)
time.sleep(2)

times = deque(maxlen=MAX_POINTS)
temps = deque(maxlen=MAX_POINTS)
hums = deque(maxlen=MAX_POINTS)
axs = deque(maxlen=MAX_POINTS)
ays = deque(maxlen=MAX_POINTS)
azs = deque(maxlen=MAX_POINTS)
gxs = deque(maxlen=MAX_POINTS)
gys = deque(maxlen=MAX_POINTS)
gzs = deque(maxlen=MAX_POINTS)

fig, axes = plt.subplots(3, 1, figsize=(10, 8))

ax1, ax2, ax3 = axes

line_temp, = ax1.plot([], [], label="temp")
line_hum, = ax1.plot([], [], label="humidity")

line_ax, = ax2.plot([], [], label="ax")
line_ay, = ax2.plot([], [], label="ay")
line_az, = ax2.plot([], [], label="az")

line_gx, = ax3.plot([], [], label="gx")
line_gy, = ax3.plot([], [], label="gy")
line_gz, = ax3.plot([], [], label="gz")


def update(_):
    while ser.in_waiting:
        raw = ser.readline().decode("utf-8", errors="ignore").strip()
        if not raw:
            continue

        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            continue

        t = data.get("time")
        if t is None:
            continue

        times.append(t)
        temps.append(data.get("temp", float("nan")))
        hums.append(data.get("humidity", float("nan")))
        axs.append(data.get("ax", float("nan")))
        ays.append(data.get("ay", float("nan")))
        azs.append(data.get("az", float("nan")))
        gxs.append(data.get("gx", float("nan")))
        gys.append(data.get("gy", float("nan")))
        gzs.append(data.get("gz", float("nan")))

    if not times:
        return

    line_temp.set_data(times, temps)
    line_hum.set_data(times, hums)
    ax1.relim()
    ax1.autoscale_view()
    ax1.legend()
    ax1.set_title("Temperature / Humidity")

    line_ax.set_data(times, axs)
    line_ay.set_data(times, ays)
    line_az.set_data(times, azs)
    ax2.relim()
    ax2.autoscale_view()
    ax2.legend()
    ax2.set_title("Acceleration")

    line_gx.set_data(times, gxs)
    line_gy.set_data(times, gys)
    line_gz.set_data(times, gzs)
    ax3.relim()
    ax3.autoscale_view()
    ax3.legend()
    ax3.set_title("Gyroscope")


ani = FuncAnimation(fig, update, interval=100)
plt.tight_layout()
plt.show()