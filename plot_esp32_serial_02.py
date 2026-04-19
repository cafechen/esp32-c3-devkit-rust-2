import json
import time
import serial
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from collections import deque

SERIAL_PORT = "COM3"   # 改成你的串口
BAUDRATE = 115200
MAX_POINTS = 200

ser = serial.Serial(SERIAL_PORT, BAUDRATE, timeout=1)
time.sleep(2)

times = deque(maxlen=MAX_POINTS)
axs = deque(maxlen=MAX_POINTS)
ays = deque(maxlen=MAX_POINTS)
azs = deque(maxlen=MAX_POINTS)
gxs = deque(maxlen=MAX_POINTS)
gys = deque(maxlen=MAX_POINTS)
gzs = deque(maxlen=MAX_POINTS)

# 小球状态（做一个“惯性演示”）
ball_pos = np.array([0.0, 0.0], dtype=float)
ball_vel = np.array([0.0, 0.0], dtype=float)

last_time = None

fig = plt.figure(figsize=(12, 8))
ax_acc = plt.subplot(2, 2, 1)
ax_gyro = plt.subplot(2, 2, 2)
ax_ball = plt.subplot(2, 2, 3)
ax_text = plt.subplot(2, 2, 4)

line_ax, = ax_acc.plot([], [], label="ax")
line_ay, = ax_acc.plot([], [], label="ay")
line_az, = ax_acc.plot([], [], label="az")

line_gx, = ax_gyro.plot([], [], label="gx")
line_gy, = ax_gyro.plot([], [], label="gy")
line_gz, = ax_gyro.plot([], [], label="gz")

ball_plot, = ax_ball.plot([], [], marker='o', markersize=18)
trail_plot, = ax_ball.plot([], [], linewidth=1)

trail_x = deque(maxlen=80)
trail_y = deque(maxlen=80)

text_box = ax_text.text(
    0.05, 0.95, "", va="top", ha="left", fontsize=11, family="monospace"
)
ax_text.axis("off")


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def update(_):
    global last_time, ball_pos, ball_vel

    got = False
    newest = None

    while ser.in_waiting:
        raw = ser.readline().decode("utf-8", errors="ignore").strip()
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            continue

        if "time" not in data:
            continue

        newest = data
        got = True

        t = float(data["time"])
        ax = float(data.get("ax", 0.0))
        ay = float(data.get("ay", 0.0))
        az = float(data.get("az", 0.0))
        gx = float(data.get("gx", 0.0))
        gy = float(data.get("gy", 0.0))
        gz = float(data.get("gz", 0.0))

        times.append(t)
        axs.append(ax)
        ays.append(ay)
        azs.append(az)
        gxs.append(gx)
        gys.append(gy)
        gzs.append(gz)

    if not got or newest is None or len(times) < 2:
        return

    t_now = times[-1]
    if last_time is None:
        last_time = t_now
        return

    dt = max(0.001, t_now - last_time)
    last_time = t_now

    ax_val = axs[-1]
    ay_val = ays[-1]
    az_val = azs[-1]
    gx_val = gxs[-1]
    gy_val = gys[-1]
    gz_val = gzs[-1]

    # 这里只做“演示型惯性”，不是精确导航
    # 假设板子平放，取 ax/ay 驱动平面运动
    accel_scale = 1.5
    damping = 0.92

    acc2d = np.array([ax_val, ay_val]) * accel_scale
    ball_vel = ball_vel + acc2d * dt
    ball_vel = ball_vel * damping
    ball_pos = ball_pos + ball_vel * dt * 8.0

    # 边界限制
    ball_pos[0] = clamp(ball_pos[0], -10, 10)
    ball_pos[1] = clamp(ball_pos[1], -10, 10)

    trail_x.append(ball_pos[0])
    trail_y.append(ball_pos[1])

    # 加速度图
    line_ax.set_data(times, axs)
    line_ay.set_data(times, ays)
    line_az.set_data(times, azs)
    ax_acc.relim()
    ax_acc.autoscale_view()
    ax_acc.legend()
    ax_acc.set_title("Acceleration")

    # 陀螺仪图
    line_gx.set_data(times, gxs)
    line_gy.set_data(times, gys)
    line_gz.set_data(times, gzs)
    ax_gyro.relim()
    ax_gyro.autoscale_view()
    ax_gyro.legend()
    ax_gyro.set_title("Gyroscope")

    # 小球惯性图
    ball_plot.set_data([ball_pos[0]], [ball_pos[1]])
    trail_plot.set_data(list(trail_x), list(trail_y))
    ax_ball.set_xlim(-10, 10)
    ax_ball.set_ylim(-10, 10)
    ax_ball.set_title("Inertia Demo")
    ax_ball.set_aspect("equal", adjustable="box")
    ax_ball.grid(True)

    # 文本面板
    text_box.set_text(
        f"time: {t_now:.3f}\n"
        f"ax: {ax_val:.3f}\n"
        f"ay: {ay_val:.3f}\n"
        f"az: {az_val:.3f}\n"
        f"gx: {gx_val:.3f}\n"
        f"gy: {gy_val:.3f}\n"
        f"gz: {gz_val:.3f}\n"
        f"\nball_x: {ball_pos[0]:.2f}\n"
        f"ball_y: {ball_pos[1]:.2f}\n"
        f"vel_x: {ball_vel[0]:.2f}\n"
        f"vel_y: {ball_vel[1]:.2f}"
    )


ani = FuncAnimation(fig, update, interval=50)
plt.tight_layout()
plt.show()