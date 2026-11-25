// Auto-generated ESP-IDF example for BNO055 (Demo).
// Mirrors the Arduino BNO055_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BNO055.h"

static void on_int(uint8_t status) { printf("INT 0x%02X\n", (unsigned)status); }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x29;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BNO055Full imu(connection);                                         // Create BNO055 driver, (connection) → void
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
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
    printf("mode %u\n", (unsigned)imu.get_mode());
    vTaskDelay(pdMS_TO_TICKS(5000));
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
    vTaskDelay(pdMS_TO_TICKS(500));
    imu.off_interrupt();                                                // Unsubscribe, () → void
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
    printf("interrupt status 0x%02X\n", (unsigned)imu.poll_interrupt());
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
    printf("system status %u, error %u\n", (unsigned)status, (unsigned)error);
    imu.reset();                                                        // Reset the chip, () → void
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
