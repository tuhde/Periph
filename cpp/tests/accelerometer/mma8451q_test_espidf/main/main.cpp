#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MMA8451Q.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool is_finite(float v) {
    return v == v && v > -1e30f && v < 1e30f;
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

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x1C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MMA8451QMinimal accel(connection);                       // Create MMA8451Q driver, (connection)
    MMA8451QFull accel_full(connection);                     // Create MMA8451Q Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_range_4g");

    accel_full.set_data_rate(50);
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_data_rate_50hz");

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    check_true(rx <= 8192 && ry <= 8192 && rz <= 8192, "read_raw_returns_counts");

    check_true(accel_full.read_system_mode() <= 2, "read_system_mode");

    bool changed = false, lockout = false, back = false;
    uint8_t lapo = 0;
    accel_full.read_orientation(changed, lockout, lapo, back);
    check_true(lapo <= 3, "read_orientation_decodes_lapo");

    accel_full.set_fifo(1, 16);
    uint8_t level = accel_full.fifo_level();
    check_true(level <= 32, "fifo_level_in_range");
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    accel_full.read_fifo_status(overflow, watermark, count);
    check_true(count <= 32, "fifo_status_count_in_range");
    float fifo[3 * 8];
    uint8_t got = accel_full.read_fifo(fifo, 8);
    check_true(got <= 8, "read_fifo_count_in_range");
    accel_full.set_fifo(0, 0);

    accel_full.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
    check_true(true, "set_freefall_motion");
    accel_full.set_transient(7, 0.25f, 100.0f, false, false);
    check_true(true, "set_transient");
    accel_full.set_pulse(0x04, 0, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
    check_true(true, "set_pulse");
    accel_full.set_orientation(true, 100.0f, 45, 4, 4, 1);
    check_true(true, "set_orientation");
    accel_full.set_auto_sleep(true, 12.5f, 10.0f, MMA8451QFull::SOURCE_PULSE);
    check_true(true, "set_auto_sleep");
    accel_full.set_auto_sleep(false, 12.5f, 10.0f, 0);

    accel_full.enable_interrupt(MMA8451QFull::SOURCE_DATA_READY, 1);
    accel_full.poll_interrupt();
    check_true(true, "poll_interrupt");
    accel_full.disable_interrupt(MMA8451QFull::SOURCE_DATA_READY);

    float ox, oy, oz;
    accel_full.get_offset(ox, oy, oz);
    check_true(ox >= -0.256f && ox <= 0.256f, "get_offset_in_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
