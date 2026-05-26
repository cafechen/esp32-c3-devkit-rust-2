#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <Adafruit_SHTC3.h>
#include <ICM42670P.h>
#include <NimBLEDevice.h>

// ============================================================
// 滑雪动作识别 IMU 节点固件
// ------------------------------------------------------------
// 使用方式：
// 1. 腰部/骶骨节点烧录前设置 SKI_SENSOR_SIDE 为 "W"。
// 2. 左小腿节点烧录前设置为 "L"，右小腿节点烧录前设置为 "R"。
// 3. 上电后保持传感器静止数秒，固件会自动校准陀螺仪零偏。
// 4. 打开 Android 客户端或 index.html，连接三个节点后点击“静止校准”。
//
// 重要说明：
// - 传感器端只做采集、陀螺仪零偏、轻量滤波和基础姿态估计。
// - 滑雪动作指标、左右对比、窗口分段、评分和模型推理放在手机端完成。
// - 六轴 IMU 没有磁力计，Yaw/航向角会随时间漂移；Yaw 只作短时间动作相位参考。
// ============================================================

// ====== 本设备身份：腰部 W / 左小腿 L / 右小腿 R ======
// 同一份固件分别改这个值后烧录到三个 ESP32。
#define SKI_SENSOR_SIDE "W"

// ====== 安装方向修正 ======
// 如果网页中雪板向左倾，立刃角却向右变化，把对应符号从 1 改成 -1。
// 常见做法：先烧录默认值，平放校准后用手向同一方向倾斜两块板，
// 看网页曲线是否同向；如果某一块反了，就只改那一块的 SKI_EDGE_SIGN。
#define SKI_EDGE_SIGN 1.0f
#define SKI_PITCH_SIGN 1.0f

// ====== I2C 引脚 ======
// 当前仓库原代码使用 GPIO7/GPIO8；如果你的 ESP32-C5 开发板实际是 GPIO8/GPIO9，
// 请在这里改动，不需要改其它逻辑。
#define I2C_SDA 7
#define I2C_SCL 8

// ====== 采样频率 ======
// 100Hz 采样适合保留原始 IMU 数据，便于后续电脑端回测和重算算法。
// 如果三路 BLE 在现场不稳定，可以临时改为 20ms，也就是 50Hz。
const unsigned long SAMPLE_INTERVAL_MS = 10;

// ====== BLE UART UUID，网页端使用同一组 UUID 连接 ======
#define SERVICE_UUID      "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

Adafruit_SHTC3 shtc3;
ICM42670 icm(Wire, 0);  // 0 表示默认 I2C 地址 0x68

bool sht_ok = false;
bool imu_ok = false;
bool deviceConnected = false;

static NimBLECharacteristic* txCharacteristic = nullptr;
static NimBLECharacteristic* rxCharacteristic = nullptr;

// ====== IMU 原始值换算系数 ======
// 这些系数要和 startAccel/startGyro 设置的量程一致。
const float ACC_G_PER_LSB = 16.0f / 32768.0f;        // ±16g
const float GYRO_DPS_PER_LSB = 2000.0f / 32768.0f;   // ±2000 deg/s

// ====== 滤波和姿态融合参数 ======
const float LPF_ALPHA_ACC = 0.25f;       // 加速度低通滤波，越大响应越快，越小越稳
const float LPF_ALPHA_GYRO = 0.25f;      // 陀螺仪低通滤波
const float COMPLEMENTARY_ALPHA = 0.98f; // 互补滤波：陀螺短期稳定，加速度长期纠偏
const float GYRO_DEADBAND_DPS = 3.0f;    // 静止附近的小角速度归零，减少积分漂移

// ====== 滑雪动作识别阈值，可根据实际数据调参 ======
const float EDGE_ACTIVE_DEG = 8.0f;          // 认为已经明显立刃的角度
const float EDGE_FLAT_DEG = 4.0f;            // 认为接近平板的角度
const float EDGE_CHANGE_DEG = 6.0f;          // 换刃区间：接近 0 度且符号准备变化
const float EDGE_CHANGE_GYRO_DPS = 35.0f;    // Roll 角速度超过该值，说明正在快速换刃
const float QUIET_GYRO_DPS = 10.0f;          // 判断静止/平稳用的角速度阈值
const float QUIET_ACC_LOW_G = 0.92f;
const float QUIET_ACC_HIGH_G = 1.08f;

// ====== 传感器状态 ======
float ax_g = 0, ay_g = 0, az_g = 0;
float gx_dps = 0, gy_dps = 0, gz_dps = 0;

float filt_ax = 0, filt_ay = 0, filt_az = 0;
float filt_gx = 0, filt_gy = 0, filt_gz = 0;

float acc_mag = 0;
float gyro_mag = 0;

// 姿态角，单位：弧度。网页端也会显示角度。
float roll = 0;
float pitch = 0;
float yaw = 0;

// 开机静止校准得到的陀螺仪零偏。
float gyro_bias_x = 0;
float gyro_bias_y = 0;
float gyro_bias_z = 0;
bool gyro_calibrated = false;

// 静止校准得到的“雪板平放基准姿态”。
// 后续输出的 edge/pitch/yaw 都是相对这个基准的值。
float base_roll = 0;
float base_pitch = 0;
float base_yaw = 0;
bool reference_calibrated = false;
bool reference_calibration_requested = false;

// 每次检测到姿态方向明显切换就递增，主要用于调试和旧页面兼容。
unsigned long edge_change_count = 0;
int last_edge_sign = 0;
unsigned long sample_seq = 0;

enum SkiPhase {
  PHASE_STILL = 0,       // 静止/等待
  PHASE_FLAT = 1,        // 平板滑行或立刃不足
  PHASE_LEFT_EDGE = 2,   // 当前 Roll 符号对应一侧刃
  PHASE_RIGHT_EDGE = 3,  // 当前 Roll 符号对应另一侧刃
  PHASE_EDGE_CHANGE = 4  // 换刃过程
};

SkiPhase skiPhase = PHASE_STILL;

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
    if (value.empty()) return;

    Serial.print("BLE RX: ");
    Serial.println(value.c_str());

    // 网页端点击“静止校准”时会发送 CAL。
    // 这里不直接在回调里读 I2C，避免 BLE 回调阻塞太久；只设置标志位，
    // 主循环下一轮执行真正的校准。
    if (value.find("CAL") != std::string::npos || value.find("ZERO") != std::string::npos) {
      reference_calibration_requested = true;
    }
  }
};

float radToDeg(float v) {
  return v * 180.0f / PI;
}

float applyDeadband(float v, float th) {
  return (fabsf(v) < th) ? 0.0f : v;
}

void formatJsonFloat(char* out, size_t outSize, float value, int decimals) {
  // JSON 不支持 NaN/Infinity。传感器缺失时输出 null，
  // 这样网页和日志解析器都能正常处理。
  if (isnan(value) || isinf(value)) {
    snprintf(out, outSize, "null");
    return;
  }

  char fmt[8];
  snprintf(fmt, sizeof(fmt), "%%.%df", decimals);
  snprintf(out, outSize, fmt, value);
}

int signWithDeadzone(float v, float deadzone) {
  if (v > deadzone) return 1;
  if (v < -deadzone) return -1;
  return 0;
}

const char* phaseName(SkiPhase s) {
  switch (s) {
    case PHASE_STILL: return "STILL";
    case PHASE_FLAT: return "FLAT";
    case PHASE_LEFT_EDGE: return "LEFT_EDGE";
    case PHASE_RIGHT_EDGE: return "RIGHT_EDGE";
    case PHASE_EDGE_CHANGE: return "EDGE_CHANGE";
    default: return "UNKNOWN";
  }
}

void setupBLE() {
  String deviceName = String("SKI-IMU-") + SKI_SENSOR_SIDE;
  NimBLEDevice::init(deviceName.c_str());
  // 提高 ATT MTU，降低较长 JSON 通知被拆包/截断的概率。
  // Web Bluetooth 端仍然按换行符拼包，所以即使拆包也可以正确解析。
  NimBLEDevice::setMTU(185);

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
  pAdvertising->setName(deviceName.c_str());
  pAdvertising->start();
}

bool readImuRaw(inv_imu_sensor_event_t& evt) {
  return imu_ok && icm.getDataFromRegisters(evt) == 0;
}

void calibrateGyroBias() {
  const int DISCARD = 80;  // 丢掉刚开始的样本，等传感器输出稳定
  const int N = 300;       // 用 300 个样本平均陀螺零偏

  float sx = 0, sy = 0, sz = 0;
  int valid = 0;

  Serial.println("陀螺仪零偏校准：请保持设备静止约 2 秒");
  delay(500);

  for (int i = 0; i < DISCARD; i++) {
    inv_imu_sensor_event_t evt;
    readImuRaw(evt);
    delay(5);
  }

  for (int i = 0; i < N; i++) {
    inv_imu_sensor_event_t evt;
    if (readImuRaw(evt)) {
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

void calibrateReferencePose() {
  // 这个校准用于建立坐标系零点：把“当前平放、平行、静止的雪板姿态”
  // 作为 edge=0、pitch=0 的参考。
  const int N = 120;
  float sr = 0, sp = 0, sy = 0;
  int valid = 0;

  Serial.println("姿态零点校准：请保持雪板/雪鞋平放且静止");

  for (int i = 0; i < N; i++) {
    inv_imu_sensor_event_t evt;
    if (readImuRaw(evt)) {
      // 静止校准时主要看重力方向。直接从加速度算 Roll/Pitch，
      // 可以避免校准期间陀螺积分的小漂移影响零点。
      float ax = evt.accel[0] * ACC_G_PER_LSB;
      float ay = evt.accel[1] * ACC_G_PER_LSB;
      float az = evt.accel[2] * ACC_G_PER_LSB;
      sr += atan2f(ay, az);
      sp += atan2f(-ax, sqrtf(ay * ay + az * az));
      sy += yaw;
      valid++;
    }
    delay(10);
  }

  if (valid > 0) {
    base_roll = sr / valid;
    base_pitch = sp / valid;
    base_yaw = sy / valid;
    reference_calibrated = true;
    edge_change_count = 0;
    last_edge_sign = 0;
  }

  Serial.print("base_roll_deg="); Serial.println(radToDeg(base_roll), 2);
  Serial.print("base_pitch_deg="); Serial.println(radToDeg(base_pitch), 2);
  Serial.print("base_yaw_deg="); Serial.println(radToDeg(base_yaw), 2);
}

void updateSkiPhase(float edgeDeg, float rollRateDps) {
  bool isQuiet = (gyro_mag < QUIET_GYRO_DPS &&
                  acc_mag > QUIET_ACC_LOW_G &&
                  acc_mag < QUIET_ACC_HIGH_G);

  int edgeSign = signWithDeadzone(edgeDeg, EDGE_ACTIVE_DEG);
  bool nearFlat = fabsf(edgeDeg) < EDGE_FLAT_DEG;
  bool changingEdge = fabsf(edgeDeg) < EDGE_CHANGE_DEG && fabsf(rollRateDps) > EDGE_CHANGE_GYRO_DPS;

  if (isQuiet && nearFlat) {
    skiPhase = PHASE_STILL;
  } else if (changingEdge) {
    skiPhase = PHASE_EDGE_CHANGE;
  } else if (edgeSign == 0) {
    skiPhase = PHASE_FLAT;
  } else if (edgeSign > 0) {
    skiPhase = PHASE_LEFT_EDGE;
  } else {
    skiPhase = PHASE_RIGHT_EDGE;
  }

  // 记录换刃次数：从一个明显立刃符号切换到另一个明显立刃符号。
  if (edgeSign != 0 && last_edge_sign != 0 && edgeSign != last_edge_sign) {
    edge_change_count++;
  }
  if (edgeSign != 0) {
    last_edge_sign = edgeSign;
  }
}

void setup() {
  Serial.begin(115200);

  unsigned long start = millis();
  while (!Serial && millis() - start < 2500) {
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
    // 100Hz 传感器输出，主循环 50Hz 读取。量程选大一些以覆盖滑雪快速换刃。
    icm.startAccel(100, 16);
    icm.startGyro(100, 2000);
    imu_ok = true;
  }

  if (imu_ok) {
    calibrateGyroBias();
  }

  setupBLE();

  Serial.print("device=SKI-IMU-"); Serial.println(SKI_SENSOR_SIDE);
  Serial.print("sht_ok="); Serial.println(sht_ok ? "true" : "false");
  Serial.print("imu_ok="); Serial.println(imu_ok ? "true" : "false");
  Serial.println("可通过 BLE RX 发送 CAL 执行静止姿态零点校准");
}

void loop() {
  static unsigned long last_sample = 0;
  unsigned long now = millis();

  if (reference_calibration_requested) {
    reference_calibration_requested = false;
    calibrateReferencePose();
  }

  if (now - last_sample < SAMPLE_INTERVAL_MS) {
    delay(1);
    return;
  }

  float dt = (now - last_sample) / 1000.0f;
  if (dt <= 0 || dt > 0.1f) dt = SAMPLE_INTERVAL_MS / 1000.0f;
  last_sample = now;

  float temp = NAN;
  float hum = NAN;

  if (sht_ok) {
    sensors_event_t hum_event, temp_event;
    if (shtc3.getEvent(&hum_event, &temp_event)) {
      temp = temp_event.temperature;
      hum = hum_event.relative_humidity;
    }
  }

  if (imu_ok) {
    inv_imu_sensor_event_t evt;
    if (readImuRaw(evt)) {
      // 原始值换算到工程单位。
      ax_g = evt.accel[0] * ACC_G_PER_LSB;
      ay_g = evt.accel[1] * ACC_G_PER_LSB;
      az_g = evt.accel[2] * ACC_G_PER_LSB;

      gx_dps = evt.gyro[0] * GYRO_DPS_PER_LSB - gyro_bias_x;
      gy_dps = evt.gyro[1] * GYRO_DPS_PER_LSB - gyro_bias_y;
      gz_dps = evt.gyro[2] * GYRO_DPS_PER_LSB - gyro_bias_z;

      // 一阶低通滤波，抑制雪面震动和传感器噪声。
      filt_ax = LPF_ALPHA_ACC * ax_g + (1.0f - LPF_ALPHA_ACC) * filt_ax;
      filt_ay = LPF_ALPHA_ACC * ay_g + (1.0f - LPF_ALPHA_ACC) * filt_ay;
      filt_az = LPF_ALPHA_ACC * az_g + (1.0f - LPF_ALPHA_ACC) * filt_az;

      filt_gx = LPF_ALPHA_GYRO * gx_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gx;
      filt_gy = LPF_ALPHA_GYRO * gy_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gy;
      filt_gz = LPF_ALPHA_GYRO * gz_dps + (1.0f - LPF_ALPHA_GYRO) * filt_gz;

      filt_gx = applyDeadband(filt_gx, GYRO_DEADBAND_DPS);
      filt_gy = applyDeadband(filt_gy, GYRO_DEADBAND_DPS);
      filt_gz = applyDeadband(filt_gz, GYRO_DEADBAND_DPS);

      acc_mag = sqrtf(filt_ax * filt_ax + filt_ay * filt_ay + filt_az * filt_az);
      gyro_mag = sqrtf(filt_gx * filt_gx + filt_gy * filt_gy + filt_gz * filt_gz);

      // 加速度推算重力方向，得到长期稳定的 Roll/Pitch。
      float acc_roll = atan2f(filt_ay, filt_az);
      float acc_pitch = atan2f(-filt_ax, sqrtf(filt_ay * filt_ay + filt_az * filt_az));

      // 互补滤波：陀螺仪积分负责短时间动态，加速度负责慢慢纠正漂移。
      roll = COMPLEMENTARY_ALPHA * (roll + filt_gx * DEG_TO_RAD * dt)
           + (1.0f - COMPLEMENTARY_ALPHA) * acc_roll;

      pitch = COMPLEMENTARY_ALPHA * (pitch + filt_gy * DEG_TO_RAD * dt)
            + (1.0f - COMPLEMENTARY_ALPHA) * acc_pitch;

      // 六轴没有绝对航向，Yaw 只能短期参考，所以做一点泄露衰减。
      yaw += filt_gz * DEG_TO_RAD * dt;
      yaw *= 0.9995f;
    }
  }

  sample_seq++;

  float edgeDeg = SKI_EDGE_SIGN * radToDeg(roll - base_roll);
  float pitchDeg = SKI_PITCH_SIGN * radToDeg(pitch - base_pitch);
  float yawDeg = radToDeg(yaw - base_yaw);
  float rollRateDps = filt_gx;

  updateSkiPhase(edgeDeg, rollRateDps);

  // 串口输出完整 JSON，方便后续离线分析和调参。
  // BLE 输出紧凑 CSV，减少每条通知长度，手机端再转换成结构化 JSONL 保存。
  //
  // BLE 格式：
  // S,id,seq,ms,ax_mg,ay_mg,az_mg,gx_dps10,gy_dps10,gz_dps10,roll_cd,pitch_cd,yaw_cd,cal
  // 例：S,W,1024,12345,12,-8,1003,4,-2,1,35,-12,80,1
  char serialJson[512];
  char bleLine[160];
  char tempJson[16];
  char humJson[16];

  formatJsonFloat(tempJson, sizeof(tempJson), temp, 1);
  formatJsonFloat(humJson, sizeof(humJson), hum, 1);

  snprintf(
    serialJson, sizeof(serialJson),
    "{\"id\":\"%s\",\"seq\":%lu,\"t\":%.3f,\"ms\":%lu,\"tp\":%s,\"h\":%s,"
    "\"ax\":%.2f,\"ay\":%.2f,\"az\":%.2f,"
    "\"gx\":%.1f,\"gy\":%.1f,\"gz\":%.1f,"
    "\"am\":%.3f,\"gm\":%.1f,"
    "\"r\":%.3f,\"p\":%.3f,\"y\":%.3f,"
    "\"ed\":%.1f,\"pd\":%.1f,\"yd\":%.1f,"
    "\"rr\":%.1f,\"ph\":\"%s\",\"ec\":%lu,"
    "\"cal\":%d,\"imu\":%d}\n",
    SKI_SENSOR_SIDE,
    sample_seq,
    now / 1000.0f,
    now,
    tempJson, humJson,
    ax_g, ay_g, az_g,
    gx_dps, gy_dps, gz_dps,
    acc_mag, gyro_mag,
    roll, pitch, yaw,
    edgeDeg, pitchDeg, yawDeg,
    rollRateDps,
    phaseName(skiPhase),
    edge_change_count,
    reference_calibrated ? 1 : 0,
    imu_ok ? 1 : 0
  );

  int ax_mg = (int)lroundf(ax_g * 1000.0f);
  int ay_mg = (int)lroundf(ay_g * 1000.0f);
  int az_mg = (int)lroundf(az_g * 1000.0f);
  int gx_dps10 = (int)lroundf(gx_dps * 10.0f);
  int gy_dps10 = (int)lroundf(gy_dps * 10.0f);
  int gz_dps10 = (int)lroundf(gz_dps * 10.0f);
  int roll_cd = (int)lroundf(edgeDeg * 100.0f);
  int pitch_cd = (int)lroundf(pitchDeg * 100.0f);
  int yaw_cd = (int)lroundf(yawDeg * 100.0f);

  snprintf(
    bleLine, sizeof(bleLine),
    "S,%s,%lu,%lu,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
    SKI_SENSOR_SIDE,
    sample_seq,
    now,
    ax_mg, ay_mg, az_mg,
    gx_dps10, gy_dps10, gz_dps10,
    roll_cd, pitch_cd, yaw_cd,
    reference_calibrated ? 1 : 0
  );

  Serial.println(serialJson);

  if (deviceConnected && txCharacteristic) {
    txCharacteristic->setValue((uint8_t*)bleLine, strlen(bleLine));
    txCharacteristic->notify();
  }
}
