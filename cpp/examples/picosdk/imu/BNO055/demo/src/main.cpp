#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BNO055.h"

static void on_int(uint8_t status) { printf("INT 0x%02X\n", (unsigned)status); }

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x29);
    BNO055Full imu(connection);                                         // Create BNO055 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float h, r, p, qw, qx, qy, qz, lx, ly, lz, gx, gy, gz, ax, ay, az, mx, my, mz, rx, ry, rz;
    uint8_t sys_c, gyr_c, acc_c, mag_c, status, error;
    uint8_t blob[22];
    uint8_t uid[16];
    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    bool st_acc, st_mag, st_gyr, st_mcu;

    // --- Identify the part ---
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);// Read revisions, (chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader) → void
    printf("chip 0x%02X, SW %u.%u, bootloader %u\n", (unsigned)chip_id, (unsigned)(sw_revision >> 8), (unsigned)(sw_revision & 0xFF), (unsigned)bootloader);
    imu.unique_id(uid);                                                 // Read unique ID, (out[16]) → void
    printf("unique id %02X%02X%02X%02X...\n", (unsigned)uid[0], (unsigned)uid[1], (unsigned)uid[2], (unsigned)uid[3]);

    // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        imu.quaternion(qw, qx, qy, qz);                                 // Read orientation quaternion, (w, x, y, z) → void
        imu.linear_acceleration(lx, ly, lz);                            // Read gravity-free acceleration, (x, y, z) → void m/s²
        imu.gravity(gx, gy, gz);                                        // Read gravity vector, (x, y, z) → void m/s²
        imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);             // Read calibration status, (sys, gyr, acc, mag) → void
        printf("euler %.2f %.2f %.2f  quat %.3f %.3f %.3f %.3f  cal %u%u%u%u\n", h, r, p, qw, qx, qy, qz, (unsigned)sys_c, (unsigned)gyr_c, (unsigned)acc_c, (unsigned)mag_c);
        printf("linear %.2f %.2f %.2f  gravity %.2f %.2f %.2f\n", lx, ly, lz, gx, gy, gz);
        if (imu.is_calibrated()) break;                                  // Check full calibration, () → bool
        sleep_ms(100);
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
    printf("mode %u\n", (unsigned)imu.get_mode());
    sleep_ms(5000);
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.set_calibration(blob);                                          // Restore offsets and radii, (data[22]) → void

    // --- Self-test, temperature and configuration ---
    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);                      // Run self-test, (acc, mag, gyr, mcu) → void
    printf("self-test acc %u mag %u gyr %u mcu %u\n", (unsigned)st_acc, (unsigned)st_mag, (unsigned)st_gyr, (unsigned)st_mcu);
    printf("temperature %d C\n", (int)imu.temperature());
    imu.set_temperature_source(BNO055Full::TEMP_SOURCE_GYROSCOPE);      // Select temperature sensor, (source) → void
    imu.set_axis_remap(0x24, 0x00);                                     // Remap axes, (config, sign) → void
    imu.set_power_mode(BNO055Full::POWER_NORMAL);                       // Select power mode, (mode) → void
    imu.use_external_crystal(false);                                    // Select clock source, (on) → void

    // --- Raw sensors in AMG mode ---
    imu.set_mode(BNO055Full::MODE_AMG);                                 // Select operation mode, (mode) → void
    imu.acceleration(ax, ay, az);                                       // Read raw accelerometer, (x, y, z) → void m/s²
    imu.magnetic_field(mx, my, mz);                                     // Read raw magnetometer, (x, y, z) → void µT
    imu.angular_rate(rx, ry, rz);                                       // Read raw gyroscope, (x, y, z) → void rad/s
    printf("acc %.2f %.2f %.2f  mag %.1f %.1f %.1f  gyr %.3f %.3f %.3f\n", ax, ay, az, mx, my, mz, rx, ry, rz);

    // --- Interrupts: accelerometer/fusion data ready ---
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);              // Enable interrupt source, (source) → void
    imu.on_interrupt(on_int);                                           // Subscribe to interrupts, (callback) → void
    sleep_ms(500);
    imu.off_interrupt();                                                // Unsubscribe, () → void
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
    printf("interrupt status 0x%02X\n", (unsigned)imu.poll_interrupt());
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
    printf("system status %u, error %u\n", (unsigned)status, (unsigned)error);
    imu.reset();                                                        // Reset the chip, () → void
    while (true) sleep_ms(1000);
    return 0;
}
