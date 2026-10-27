#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LSM303.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

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

    // Accelerometer slave (0x19) and magnetometer slave (0x1E) are two
    // devices on the same bus.
    i2c_device_config_t accel_cfg = {};
    accel_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    accel_cfg.device_address = 0x19;
    accel_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t accel_dev;
    i2c_master_bus_add_device(bus, &accel_cfg, &accel_dev);

    i2c_device_config_t mag_cfg = {};
    mag_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_cfg.device_address = 0x1E;
    mag_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t mag_dev;
    i2c_master_bus_add_device(bus, &mag_cfg, &mag_dev);

    I2CConnectionESPIDF accel_connection(accel_dev);
    I2CConnectionESPIDF mag_connection(mag_dev);

    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel, mag connections)
    LSM303Full sensor_full(accel_connection, mag_connection);  // Create LSM303 Full driver, (accel, mag connections)

    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);                     // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_returns_floats");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true(amag >= 0.5f && amag <= 1.5f, "acceleration_magnitude_near_1g");

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                         // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_returns_floats");
    float bmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true(bmag >= 5.0f && bmag <= 200.0f, "magnetic_magnitude_plausible");

    sensor_full.set_range(4);
    sensor_full.read_acceleration(ax, ay, az);
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_after_set_range_4g");

    sensor_full.set_mag_range(470);
    sensor_full.read_magnetic(mx, my, mz);
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_after_set_mag_range");

    float hdg = sensor_full.heading();                        // Compute compass heading, (tilt_compensate=true) → degrees
    check_true(hdg >= 0.0f && hdg < 360.0f, "heading_in_range");

    float temp = sensor_full.read_temperature_delta();
    check_true(temp == temp, "temperature_delta_is_finite");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
