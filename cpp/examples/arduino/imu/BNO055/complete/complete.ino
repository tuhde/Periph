#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x29);
BNO055Full imu(connection);                                             // Create BNO055 driver, (connection) → void

static void on_int(uint8_t status) { Serial.print("INT 0x"); Serial.println(status, HEX); }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float h, r, p, qw, qx, qy, qz, x, y, z;
    uint8_t sys_c, gyr_c, acc_c, mag_c, status, error;
    uint8_t blob[22];
    uint8_t uid[16];
    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    bool st_acc, st_mag, st_gyr, st_mcu;

    imu.euler(h, r, p);                                                 // Read fused Euler angles, (heading, roll, pitch) → void rad
                                                                        // burst read of 0x1A…0x1F, raw / 900
    imu.quaternion(qw, qx, qy, qz);                                     // Read orientation quaternion, (w, x, y, z) → void
                                                                        // raw / 16384
    imu.linear_acceleration(x, y, z);                                   // Read gravity-free acceleration, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.gravity(x, y, z);                                               // Read gravity vector, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.acceleration(x, y, z);                                          // Read raw accelerometer, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.magnetic_field(x, y, z);                                        // Read raw magnetometer, (x, y, z) → void µT
                                                                        // raw / 16
    imu.angular_rate(x, y, z);                                          // Read raw gyroscope, (x, y, z) → void rad/s
                                                                        // raw / 900
    Serial.print("temperature "); Serial.print((int)imu.temperature()); Serial.print(" C"); Serial.println();
    // imu.temperature() → int8_t °C, signed 8 bit, 1 LSB = 1 °C
    imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);                 // Read calibration status, (sys, gyr, acc, mag) → void
                                                                        // each 0 (uncalibrated) … 3 (calibrated)
    Serial.print("calibrated "); Serial.print((unsigned)imu.is_calibrated()); Serial.println();
    // imu.is_calibrated() → bool, true when all four values are 3

    Serial.print("mode "); Serial.print((unsigned)imu.get_mode()); Serial.println();
    // imu.get_mode() → uint8_t, 0x0C = NDOF
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
                                                                        // via CONFIGMODE, 19 ms + 7 ms waits
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
                                                                        // back to 9-DOF fusion

    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
                                                                        // 0x55…0x6A, read in CONFIGMODE
    imu.set_calibration(blob);                                          // Restore offsets and radii, (data[22]) → void
                                                                        // written in CONFIGMODE
    imu.set_axis_remap(0x24, 0x00);                                     // Remap axes, (config, sign) → void
                                                                        // 0x24/0x00 = identity placement
    imu.set_power_mode(BNO055Full::POWER_NORMAL);                       // Select power mode, (mode) → void
                                                                        // normal, low power or suspend
    imu.set_temperature_source(BNO055Full::TEMP_SOURCE_ACCELEROMETER);  // Select temperature sensor, (source) → void
                                                                        // accelerometer or gyroscope
    imu.use_external_crystal(false);                                    // Select clock source, (on) → void
                                                                        // internal oscillator unless a crystal is fitted

    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);                      // Run self-test, (acc, mag, gyr, mcu) → void
                                                                        // true = passed, mode restored afterwards
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
                                                                        // SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);// Read revisions, (chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader) → void
                                                                        // identification registers 0x00…0x06
    imu.unique_id(uid);                                                 // Read unique ID, (out[16]) → void
                                                                        // 16 bytes from page 1

    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);              // Enable interrupt source, (source) → void
                                                                        // INT_EN and INT_MSK on page 1
    Serial.print("interrupt status 0x"); Serial.print((unsigned)imu.poll_interrupt(), HEX); Serial.println();
    // imu.poll_interrupt() → uint8_t, INT_STA, cleared on read
    imu.on_interrupt(on_int);                                           // Subscribe to interrupts, (callback) → void
                                                                        // needs an INT pin on the connection; otherwise poll
    delay(500);
    imu.off_interrupt();                                                // Unsubscribe, () → void
                                                                        // stops the callback
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
                                                                        // clears the INT_EN and INT_MSK bits

    imu.reset();                                                        // Reset the chip, () → void
                                                                        // RST_SYS, waits 650 ms, re-enters NDOF
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
