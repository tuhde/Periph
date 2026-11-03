#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LIS3DH.h"

static int passed = 0, failed = 0;

#define LIS3DH_WAIT() vTaskDelay(pdMS_TO_TICKS(20))

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

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x18;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LIS3DHMinimal accel(connection);                        // Create LIS3DH driver, (connection)
    LIS3DHFull accel_full(connection);                      // Create LIS3DH Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");
    accel_full.set_range(2);

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);                        // Read raw counts, (x, y, z) → LSB, LSB, LSB
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 &&
               rz >= -2048 && rz <= 2047, "read_raw_in_12bit_range");

    int16_t temp = accel_full.read_temperature_delta();     // Read relative temperature, () → int16_t °C
    check_true(temp >= -128 && temp <= 127, "temperature_delta_in_range");

    accel_full.set_high_pass(0, 0);
    uint8_t ref = accel_full.reset_high_pass();             // Reset high-pass filter, () → uint8_t
    check_true(accel_full.reset_high_pass() == ref, "reset_high_pass_stable");

    LIS3DH_WAIT();                                         // wait for the next conversion (100 Hz ODR)
    uint8_t st = accel_full.read_status();                 // Read STATUS register, () → uint8_t
    check_true((st & 0x08) == 0x08, "read_status_zyxda_set");

    accel_full.set_fifo(LIS3DHFull::FIFO_FIFO, 8, false);   // Configure FIFO, (mode, watermark, trigger_int2)
    uint8_t level = accel_full.fifo_level();                // Read FIFO level, () → uint8_t
    check_true(level <= 32, "fifo_level_in_range");
    float xb[4], yb[4], zb[4];
    uint8_t n = accel_full.read_fifo(xb, yb, zb, 4);       // Drain FIFO, (x_buf, y_buf, z_buf, max_samples)
    check_true(n <= 4, "read_fifo_count");
    accel_full.set_fifo(LIS3DHFull::FIFO_BYPASS);

    accel_full.set_click(4, 1, 0.5f, 10, 20, 50, true);    // Configure click detection
    uint8_t click = accel_full.read_click_source();         // Read CLICK_SRC, () → uint8_t
    check_true(click != 0xFF, "read_click_source_byte");

    accel_full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    uint8_t int1 = accel_full.read_interrupt_source(1);    // Read INT1_SRC, () → uint8_t
    check_true(int1 != 0xFF, "read_interrupt_source_byte");

    accel_full.set_activity(0.5f, 100);
    accel_full.set_interrupt_polarity(false);
    accel_full.reboot();                                    // Reboot memory content, () → void
    accel_full.set_sdo_pullup(true);                        // Connect SDO pull-up, (connected) → void

    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_reboot");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
