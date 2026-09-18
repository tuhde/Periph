#include <Wire.h>
#include <I2CConnection.h>
#include <L3gd20h.h>

I2CConnection conn(Wire, 0x6A);
L3gd20hFull gyro(conn);

void setup() {
  Serial.begin(115200);
  Wire.begin();

  gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None
  // sets ODR=190 Hz, bandwidth=default, full-scale=±500 dps

  gyro.configure_hp_filter(L3gd20hFull::HPM_NORMAL, 0);  // Configure HPF, (mode 0-3, cutoff 0-15) -> None
  gyro.enable_hp_filter(true);                            // Enable HPF, (enable=true) -> None

  gyro.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
  gyro.enable_fifo(true);                            // Enable FIFO, (enable=true) -> None

  gyro.set_power_mode(L3gd20hFull::POWER_NORMAL);    // Set power mode, (mode='normal'/'sleep'/'power_down') -> None

  uint8_t who = gyro.who_am_i();                     // Read WHO_AM_I, () -> uint8_t
  Serial.print("WHO_AM_I: 0x"); Serial.println(who, HEX);

  int8_t temp = gyro.temperature();                  // Read temperature, () -> int8_t
  Serial.print("Temperature: "); Serial.println(temp);
}

void loop() {
  if (gyro.data_ready()) {                           // Check data ready, () -> bool
    float x, y, z;
    gyro.gyro(x, y, z);                              // Read angular rate, () -> (float, float, float) rad/s
    Serial.print("x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.println(z, 3);

    int16_t rx, ry, rz;
    gyro.gyro_raw(rx, ry, rz);                       // Read raw, () -> (int16_t, int16_t, int16_t)

    uint8_t level = gyro.fifo_level();               // FIFO level, () -> uint8_t
    if (level > 0) {
      float fx[32], fy[32], fz[32];
      uint8_t n = gyro.read_fifo(fx, fy, fz, level); // Read FIFO, (out_x, out_y, out_z, max) -> uint8_t
      Serial.print("FIFO: "); Serial.print(n); Serial.println(" samples");
    }
  }
  delay(10);
}