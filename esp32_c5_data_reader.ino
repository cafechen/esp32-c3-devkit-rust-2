#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_SHTC3.h>
#include <ICM42670P.h>
#include <NimBLEDevice.h>

#define I2C_SDA 7
#define I2C_SCL 8

const int SAMPLE_INTERVAL = 100;  // 10Hz

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

void setupBLE() {
  NimBLEDevice::init("ESP32C3-IMU");

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
  pAdvertising->setName("ESP32C3-IMU");
  pAdvertising->start();
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

  setupBLE();

  Serial.print("sht_ok=");
  Serial.println(sht_ok ? "true" : "false");
  Serial.print("imu_ok=");
  Serial.println(imu_ok ? "true" : "false");
}

void loop() {
  static unsigned long last_sample = 0;
  unsigned long now = millis();

  if (now - last_sample < SAMPLE_INTERVAL) {
    delay(5);
    return;
  }
  last_sample = now;

  float temp = NAN, hum = NAN;
  float ax = NAN, ay = NAN, az = NAN;
  float gx = NAN, gy = NAN, gz = NAN;

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
      ax = evt.accel[0];
      ay = evt.accel[1];
      az = evt.accel[2];
      gx = evt.gyro[0];
      gy = evt.gyro[1];
      gz = evt.gyro[2];
    }
  }

  char json[256];
  snprintf(
    json, sizeof(json),
    "{\"time\":%.3f,\"temp\":%.2f,\"humidity\":%.1f,"
    "\"ax\":%.3f,\"ay\":%.3f,\"az\":%.3f,"
    "\"gx\":%.3f,\"gy\":%.3f,\"gz\":%.3f}",
    now / 1000.0, temp, hum, ax, ay, az, gx, gy, gz
  );

  Serial.println(json);

  if (deviceConnected && txCharacteristic) {
    txCharacteristic->setValue((uint8_t*)json, strlen(json));
    txCharacteristic->notify();
  }
}