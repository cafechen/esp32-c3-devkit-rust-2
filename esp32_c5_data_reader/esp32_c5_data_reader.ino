#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <Adafruit_SHTC3.h>
#include <ICM42670P.h>
#include <NimBLEDevice.h>

#define I2C_SDA 7
#define I2C_SCL 8

const int SAMPLE_INTERVAL = 20;   // 50Hz

Adafruit_SHTC3 shtc3;
ICM42670 icm(Wire, 0);  // 0x68

bool sht_ok = false;
bool imu_ok = false;
bool deviceConnected = false;

static NimBLECharacteristic* txCharacteristic = nullptr;
static NimBLECharacteristic* rxCharacteristic = nullptr;

#define SERVICE_UUID      "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// ====== IMU换算系数 ======
const float ACC_G_PER_LSB = 16.0f / 32768.0f;        // ±16g
const float GYRO_DPS_PER_LSB = 2000.0f / 32768.0f;   // ±2000 dps

// ====== 步态状态机 ======
enum GaitState {
  IDLE = 0,
  STANCE = 1,
  LIFT_OFF = 2,
  SWING = 3,
  HEEL_STRIKE = 4
};

GaitState gaitState = IDLE;
unsigned long stateEnterMs = 0;
unsigned long lastHeelStrikeMs = 0;
int stepCount = 0;

// ====== 传感器滤波状态 ======
float ax_g = 0, ay_g = 0, az_g = 0;
float gx_dps = 0, gy_dps = 0, gz_dps = 0;

float filt_ax = 0, filt_ay = 0, filt_az = 0;
float filt_gx = 0, filt_gy = 0, filt_gz = 0;

float acc_mag = 0;
float gyro_mag = 0;

// 姿态角
float roll = 0;
float pitch = 0;
float yaw = 0;

// 陀螺仪偏置
float gyro_bias_x = 0;
float gyro_bias_y = 0;
float gyro_bias_z = 0;
bool gyro_calibrated = false;

// ====== 可调参数 ======
const float LPF_ALPHA_ACC = 0.25f;
const float LPF_ALPHA_GYRO = 0.25f;
const float COMPLEMENTARY_ALPHA = 0.98f;

// 静止死区
const float GYRO_DEADBAND = 5.0f;   // dps

// Idle 判断
const float GYRO_IDLE_THRESHOLD = 12.0f;  // dps
const float ACC_IDLE_LOW = 0.93f;         // g
const float ACC_IDLE_HIGH = 1.07f;        // g

// 步态阈值（先调高，避免静止误判）
const float GYRO_LIFT_THRESHOLD = 80.0f;    // dps
const float GYRO_SWING_THRESHOLD = 120.0f;  // dps
const float ACC_STRIKE_THRESHOLD = 1.25f;   // g
const float GYRO_STANCE_THRESHOLD = 30.0f;  // dps
const float MIN_STEP_INTERVAL_MS = 250.0f;

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    deviceConnected = true;
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
    deviceConnected = false;
    NimBLEDevice::startAdvertising();
  }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
    std::string value = pCharacteristic->getValue();
    if (!value.empty()) {
      Serial.print("BLE RX: ");
      Serial.println(value.c_str());
    }
  }
};

const char* gaitStateName(GaitState s) {
  switch (s) {
    case IDLE: return "IDLE";
    case STANCE: return "STANCE";
    case LIFT_OFF: return "LIFT_OFF";
    case SWING: return "SWING";
    case HEEL_STRIKE: return "HEEL_STRIKE";
    default: return "UNKNOWN";
  }
}

float applyDeadband(float v, float th) {
  return (fabs(v) < th) ? 0.0f : v;
}

void setupBLE() {
  NimBLEDevice::init("ESP32C3-GAIT");

  NimBLEServer* pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  txCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_TX,
    NIMBLE_PROPERTY::NOTIFY
  );

  rxCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_RX,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  rxCharacteristic->setCallbacks(new RxCallbacks());

  pService->start();

  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setName("ESP32C3-GAIT");
  pAdvertising->start();
}

void calibrateGyroBias() {
  const int DISCARD = 100;   // 丢弃前面的不稳定样本
  const int N = 400;         // 多取一点平均更稳

  float sx = 0, sy = 0, sz = 0;
  int valid = 0;

  Serial.println("Calibrating gyro... keep device still for 5 seconds");
  delay(2000);

  for (int i = 0; i < DISCARD; i++) {
    inv_imu_sensor_event_t evt;
    icm.getDataFromRegisters(evt);
    delay(5);
  }

  for (int i = 0; i < N; i++) {
    inv_imu_sensor_event_t evt;
    if (icm.getDataFromRegisters(evt) == 0) {
      sx += evt.gyro[0] * GYRO_DPS_PER_LSB;
      sy += evt.gyro[1] * GYRO_DPS_PER_LSB;
      sz += evt.gyro[2] * GYRO_DPS_PER_LSB;
      valid++;
    }
    delay(5);
  }

  if (valid > 0) {
    gyro_bias_x = sx / valid;
    gyro_bias_y = sy / valid;
    gyro_bias_z = sz / valid;
    gyro_calibrated = true;
  }

  Serial.print("gyro_bias_x="); Serial.println(gyro_bias_x, 4);
  Serial.print("gyro_bias_y="); Serial.println(gyro_bias_y, 4);
  Serial.print("gyro_bias_z="); Serial.println(gyro_bias_z, 4);
}

void updateGaitState(unsigned long nowMs) {
  bool isIdle = (gyro_mag < GYRO_IDLE_THRESHOLD &&
                 acc_mag > ACC_IDLE_LOW &&
                 acc_mag < ACC_IDLE_HIGH);

  switch (gaitState) {
    case IDLE:
      if (!isIdle && gyro_mag > GYRO_LIFT_THRESHOLD) {
        gaitState = LIFT_OFF;
        stateEnterMs = nowMs;
      } else if (!isIdle) {
        gaitState = STANCE;
        stateEnterMs = nowMs;
      }
      break;

    case STANCE:
      if (isIdle) {
        gaitState = IDLE;
        stateEnterMs = nowMs;
      } else if (gyro_mag > GYRO_LIFT_THRESHOLD) {
        gaitState = LIFT_OFF;
        stateEnterMs = nowMs;
      }
      break;

    case LIFT_OFF:
      if (gyro_mag > GYRO_SWING_THRESHOLD) {
        gaitState = SWING;
        stateEnterMs = nowMs;
      } else if (isIdle) {
        gaitState = IDLE;
        stateEnterMs = nowMs;
      } else if (gyro_mag < GYRO_STANCE_THRESHOLD) {
        gaitState = STANCE;
        stateEnterMs = nowMs;
      }
      break;

    case SWING:
      if (acc_mag > ACC_STRIKE_THRESHOLD &&
          (nowMs - lastHeelStrikeMs) > MIN_STEP_INTERVAL_MS) {
        gaitState = HEEL_STRIKE;
        stateEnterMs = nowMs;
        lastHeelStrikeMs = nowMs;
        stepCount++;
      }
      break;

    case HEEL_STRIKE:
      if (isIdle) {
        gaitState = IDLE;
        stateEnterMs = nowMs;
      } else if (gyro_mag < GYRO_STANCE_THRESHOLD) {
        gaitState = STANCE;
        stateEnterMs = nowMs;
      } else if (nowMs - stateEnterMs > 120) {
        gaitState = STANCE;
        stateEnterMs = nowMs;
      }
      break;
  }
}

void setup() {
  Serial.begin(115200);

  unsigned long start = millis();
  while (!Serial && millis() - start < 5000) {
    delay(10);
  }

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);
  delay(100);

  if (shtc3.begin()) {
    sht_ok = true;
  }

  int ret = icm.begin();
  if (ret == 0) {
    icm.startAccel(100, 16);
    icm.startGyro(100, 2000);
    imu_ok = true;
  }

  if (imu_ok) {
    calibrateGyroBias();
  }

  setupBLE();
  stateEnterMs = millis();

  Serial.print("sht_ok=");
  Serial.println(sht_ok ? "true" : "false");
  Serial.print("imu_ok=");
  Serial.println(imu_ok ? "true" : "false");
}

void loop() {
  static unsigned long last_sample = 0;
  unsigned long now = millis();

  if (now - last_sample < SAMPLE_INTERVAL) {
    delay(1);
    return;
  }

  float dt = (now - last_sample) / 1000.0f;
  if (dt <= 0 || dt > 0.1f) dt = SAMPLE_INTERVAL / 1000.0f;
  last_sample = now;

  float temp = NAN, hum = NAN;

  if (sht_ok) {
    sensors_event_t hum_event, temp_event;
    if (shtc3.getEvent(&hum_event, &temp_event)) {
      temp = temp_event.temperature;
      hum = hum_event.relative_humidity;
    }
  }

  if (imu_ok) {
    inv_imu_sensor_event_t evt;
    if (icm.getDataFromRegisters(evt) == 0) {
      ax_g = evt.accel[0] * ACC_G_PER_LSB;
      ay_g = evt.accel[1] * ACC_G_PER_LSB;
      az_g = evt.accel[2] * ACC_G_PER_LSB;

      gx_dps = evt.gyro[0] * GYRO_DPS_PER_LSB - gyro_bias_x;
      gy_dps = evt.gyro[1] * GYRO_DPS_PER_LSB - gyro_bias_y;
      gz_dps = evt.gyro[2] * GYRO_DPS_PER_LSB - gyro_bias_z;

      filt_ax = LPF_ALPHA_ACC * ax_g + (1.0f - LPF_ALPHA_ACC) * filt_ax;
      filt_ay = LPF_ALPHA_ACC * ay_g + (1.0f - LPF_ALPHA_ACC) * filt_ay;
      filt_az = LPF_ALPHA_ACC * az_g + (1.0f - LPF_ALPHA_ACC) * filt_az;

      filt_gx = LPF_ALPHA_GYRO * gx_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gx;
      filt_gy = LPF_ALPHA_GYRO * gy_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gy;
      filt_gz = LPF_ALPHA_GYRO * gz_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gz;

      // 静止死区
      filt_gx = applyDeadband(filt_gx, GYRO_DEADBAND);
      filt_gy = applyDeadband(filt_gy, GYRO_DEADBAND);
      filt_gz = applyDeadband(filt_gz, GYRO_DEADBAND);

      acc_mag = sqrtf(filt_ax * filt_ax + filt_ay * filt_ay + filt_az * filt_az);
      gyro_mag = sqrtf(filt_gx * filt_gx + filt_gy * filt_gy + filt_gz * filt_gz);

      float acc_roll = atan2f(filt_ay, filt_az);
      float acc_pitch = atan2f(-filt_ax, sqrtf(filt_ay * filt_ay + filt_az * filt_az));

      roll = COMPLEMENTARY_ALPHA * (roll + filt_gx * DEG_TO_RAD * dt)
           + (1.0f - COMPLEMENTARY_ALPHA) * acc_roll;

      pitch = COMPLEMENTARY_ALPHA * (pitch + filt_gy * DEG_TO_RAD * dt)
            + (1.0f - COMPLEMENTARY_ALPHA) * acc_pitch;

      yaw += filt_gz * DEG_TO_RAD * dt;
      yaw *= 0.9995f;

      updateGaitState(now);
    }
  }

 char json[256];
snprintf(
  json, sizeof(json),
  "{\"t\":%.2f,\"tp\":%.1f,\"h\":%.1f,"
  "\"ax\":%.2f,\"ay\":%.2f,\"az\":%.2f,"
  "\"gx\":%.1f,\"gy\":%.1f,\"gz\":%.1f,"
  "\"am\":%.3f,\"gm\":%.1f,"
  "\"r\":%.2f,\"p\":%.2f,"
  "\"s\":\"%s\",\"n\":%d}\n",
  now / 1000.0,
  temp, hum,
  filt_ax, filt_ay, filt_az,
  filt_gx, filt_gy, filt_gz,
  acc_mag, gyro_mag,
  roll, pitch,
  gaitStateName(gaitState),
  stepCount
);

  Serial.println(json);

  if (deviceConnected && txCharacteristic) {
    txCharacteristic->setValue((uint8_t*)json, strlen(json));
    txCharacteristic->notify();
  }
}