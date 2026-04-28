# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an **ESP32-C5 gait analysis and sensor data visualization** project for wearable foot-mounted IMU applications. The device streams motion sensor data over BLE and serial for real-time visualization and gait phase detection.

## Architecture

### Embedded Layer (`esp32_c5_data_reader.ino`)
- **Sensors**: SHTC3 (temperature/humidity) + ICM-42670-P (6-axis IMU: accelerometer ±16g, gyroscope ±2000°/s)
- **I2C Bus**: SDA=GPIO7, SCL=GPIO8, 100kHz
- **Sampling**: 50Hz (20ms interval)
- **Sensor Processing**:
  - Low-pass filtering (alpha=0.25) for accelerometer and gyroscope
  - Gyroscope bias calibration on startup (keep device still for 5s)
  - Complementary filter for roll/pitch estimation
  - Deadband processing for gyroscope noise
  - Gait state machine with 5 states: IDLE → STANCE → LIFT_OFF → SWING → HEEL_STRIKE
- **Output**: JSON-formatted data over both UART (115200 baud) and BLE UART service

### Data Interfaces
- **Serial UART**: COM port at 115200 baud
- **BLE**: Nordic UART Service (NUS) UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
  - TX (notify): `6E400003-...`
  - RX (write): `6E400002-...`
  - Device name: `ESP32C3-GAIT`

### Visualization Clients
1. **Python matplotlib** (`sensor_plotter.py`): Real-time plotting of temperature, humidity, accelerometer, and gyroscope data
2. **Web 3D Visualizer** (`index.html`): Three.js-based 3D orientation visualization with Web Bluetooth connectivity, showing real-time gait state, step count, and sensor data

## Common Commands

### Python Dependencies
```bash
pip install pyserial matplotlib numpy
# or
pip install -r requirements.txt
```

### Run Serial Visualization
```bash
# List available ports
python list_ports.py

# Visualize sensor data (replace COM3 with your port)
python sensor_plotter.py COM3
```

### Simple Serial Read
```bash
python simple_read.py COM3
```

### Arduino Build
- Board: **ESP32C5 DevKitM-1** (or ESP32-C3)
- Required libraries:
  - `Adafruit SHTC3` or `SHTC3` by Sensirion
  - `ICM42670P` by TDK InvenSense
  - `NimBLE-Arduino` by h2zero

## Data Format

JSON output from device:
```json
{
  "t": 123.45,      // time seconds
  "tp": 25.3,       // temperature °C
  "h": 45.2,        // humidity %RH
  "ax": 0.02,       // filtered accel X (g)
  "ay": 0.01,       // filtered accel Y (g)
  "az": 1.01,       // filtered accel Z (g)
  "gx": 0.5,        // filtered gyro X (dps)
  "gy": 0.3,        // filtered gyro Y (dps)
  "gz": 0.1,        // filtered gyro Z (dps)
  "am": 1.002,      // accel magnitude
  "gm": 0.6,        // gyro magnitude
  "r": 0.01,        // roll radians
  "p": -0.02,       // pitch radians
  "s": "STANCE",    // gait state
  "n": 42           // step count
}
```

## Key Files

- [`esp32_c5_data_reader.ino`](esp32_c5_data_reader/esp32_c5_data_reader.ino) - Main Arduino firmware with gait detection
- [`sensor_plotter.py`](esp32_c5_data_reader/sensor_plotter.py) - Python matplotlib real-time visualization
- [`index.html`](esp32_c5_data_reader/index.html) - Web 3D visualizer with Web Bluetooth
- [`serial_plotter.py`](esp32_c5_data_reader/serial_plotter.py) - Alternative basic ADC plotter
- [`list_ports.py`](esp32_c5_data_reader/list_ports.py) - Utility to list serial ports

## Gait Detection Tuning Parameters

Located in `esp32_c5_data_reader.ino`:
- `GYRO_LIFT_THRESHOLD`: Lift-off detection (default 80 dps)
- `GYRO_SWING_THRESHOLD`: Swing phase entry (default 120 dps)
- `ACC_STRIKE_THRESHOLD`: Heel-strike detection (default 1.25 g)
- `LPF_ALPHA_ACC` / `LPF_ALPHA_GYRO`: Filter cutoff

## Notes

- All code comments and documentation are in Chinese
- Baud rate is fixed at **115200** across all serial tools
- The IMU gyro bias calibrates automatically on startup - keep device stationary for 5 seconds after power-on
- Gait state machine is designed for foot-mounted IMU tracking walking phases
