#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x29);
BNO055Full imu(connection);                                             // Create BNO055 driver, (connection) → void

static void on_int(uint8_t status) { Serial.print("INT 0x"); Serial.println(status, HEX); }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float h, r, p, qw, qx, qy, qz, lx, ly, lz, gx, gy, gz, ax, ay, az, mx, my, mz, rx, ry, rz;
    uint8_t sys_c, gyr_c, acc_c, mag_c, status, error;
    uint8_t blob[22];
    uint8_t uid[16];
    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    bool st_acc, st_mag, st_gyr, st_mcu;

    // --- Identify the part ---
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);// Read revisions, (chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader) → void
    Serial.print("chip 0x"); Serial.print((unsigned)chip_id, HEX); Serial.print(", SW "); Serial.print((unsigned)(sw_revision >> 8)); Serial.print("."); Serial.print((unsigned)(sw_revision & 0xFF)); Serial.print(", bootloader "); Serial.print((unsigned)bootloader); Serial.println();
    imu.unique_id(uid);                                                 // Read unique ID, (out[16]) → void
    Serial.print("unique id "); Serial.print((unsigned)uid[0], HEX); Serial.print((unsigned)uid[1], HEX); Serial.print((unsigned)uid[2], HEX); Serial.print((unsigned)uid[3], HEX); Serial.print("..."); Serial.println();

    // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        imu.quaternion(qw, qx, qy, qz);                                 // Read orientation quaternion, (w, x, y, z) → void
        imu.linear_acceleration(lx, ly, lz);                            // Read gravity-free acceleration, (x, y, z) → void m/s²
        imu.gravity(gx, gy, gz);                                        // Read gravity vector, (x, y, z) → void m/s²
        imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);             // Read calibration status, (sys, gyr, acc, mag) → void
        Serial.print("euler "); Serial.print(h, 3); Serial.print(" "); Serial.print(r, 3); Serial.print(" "); Serial.print(p, 3); Serial.print("  quat "); Serial.print(qw, 3); Serial.print(" "); Serial.print(qx, 3); Serial.print(" "); Serial.print(qy, 3); Serial.print(" "); Serial.print(qz, 3); Serial.print("  cal "); Serial.print((unsigned)sys_c); Serial.print((unsigned)gyr_c); Serial.print((unsigned)acc_c); Serial.print((unsigned)mag_c); Serial.println();
        Serial.print("linear "); Serial.print(lx, 3); Serial.print(" "); Serial.print(ly, 3); Serial.print(" "); Serial.print(lz, 3); Serial.print("  gravity "); Serial.print(gx, 3); Serial.print(" "); Serial.print(gy, 3); Serial.print(" "); Serial.print(gz, 3); Serial.println();
        if (imu.is_calibrated()) break;                                  // Check full calibration, () → bool
        delay(100);
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
    Serial.print("mode "); Serial.print((unsigned)imu.get_mode()); Serial.println();
    delay(5000);
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.set_calibration(blob);                                          // Restore offsets and radii, (data[22]) → void

    // --- Self-test, temperature and configuration ---
    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);                      // Run self-test, (acc, mag, gyr, mcu) → void
    Serial.print("self-test acc "); Serial.print((unsigned)st_acc); Serial.print(" mag "); Serial.print((unsigned)st_mag); Serial.print(" gyr "); Serial.print((unsigned)st_gyr); Serial.print(" mcu "); Serial.print((unsigned)st_mcu); Serial.println();
    Serial.print("temperature "); Serial.print((int)imu.temperature()); Serial.print(" C"); Serial.println();
    imu.set_temperature_source(BNO055Full::TEMP_SOURCE_GYROSCOPE);      // Select temperature sensor, (source) → void
    imu.set_axis_remap(0x24, 0x00);                                     // Remap axes, (config, sign) → void
    imu.set_power_mode(BNO055Full::POWER_NORMAL);                       // Select power mode, (mode) → void
    imu.use_external_crystal(false);                                    // Select clock source, (on) → void

    // --- Raw sensors in AMG mode ---
    imu.set_mode(BNO055Full::MODE_AMG);                                 // Select operation mode, (mode) → void
    imu.acceleration(ax, ay, az);                                       // Read raw accelerometer, (x, y, z) → void m/s²
    imu.magnetic_field(mx, my, mz);                                     // Read raw magnetometer, (x, y, z) → void µT
    imu.angular_rate(rx, ry, rz);                                       // Read raw gyroscope, (x, y, z) → void rad/s
    Serial.print("acc "); Serial.print(ax, 3); Serial.print(" "); Serial.print(ay, 3); Serial.print(" "); Serial.print(az, 3); Serial.print("  mag "); Serial.print(mx, 3); Serial.print(" "); Serial.print(my, 3); Serial.print(" "); Serial.print(mz, 3); Serial.print("  gyr "); Serial.print(rx, 3); Serial.print(" "); Serial.print(ry, 3); Serial.print(" "); Serial.print(rz, 3); Serial.println();

    // --- Interrupts: accelerometer/fusion data ready ---
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);              // Enable interrupt source, (source) → void
    imu.on_interrupt(on_int);                                           // Subscribe to interrupts, (callback) → void
    delay(500);
    imu.off_interrupt();                                                // Unsubscribe, () → void
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
    Serial.print("interrupt status 0x"); Serial.print((unsigned)imu.poll_interrupt(), HEX); Serial.println();
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
    Serial.print("system status "); Serial.print((unsigned)status); Serial.print(", error "); Serial.print((unsigned)error); Serial.println();
    imu.reset();                                                        // Reset the chip, () → void
}

void loop() {
    delay(1000);
}
