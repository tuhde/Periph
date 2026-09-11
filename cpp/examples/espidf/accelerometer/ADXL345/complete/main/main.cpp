#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADXL345.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x53,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADXL345Full accel(connection);                          // Create ADXL345 Full driver, (connection, spi=false)

    accel.set_range(4);                                     // Set measurement range, (range_g) → g
    accel.set_data_rate(200);                               // Set output data rate, (rate_hz) → Hz
    accel.set_low_power(false);                             // Set low-power mode, (enabled) → None
    accel.calibrate_offset(0.0f, 0.0f, 1.0f, 64);           // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → g, g, g
    accel.set_tap_detection(0.5f, 10.0f);                   // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → g, ms
    accel.set_double_tap(50.0f, 200.0f);                    // Configure double-tap, (latency_ms, window_ms) → ms, ms
    accel.set_fifo_mode(ADXL345Full::FIFO_STREAM, 16);      // Configure FIFO, (mode, samples=16) → None
    accel.set_interrupt(ADXL345Full::INT_WATERMARK, true, 1);// Configure interrupt, (source, enabled, pin=1) → None

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g

    float xs[32], ys[32], zs[32];
    uint8_t n = accel.read_fifo(xs, ys, zs, 32);            // Drain the FIFO, (x_buf, y_buf, z_buf, max_samples) → count
    uint8_t count = accel.fifo_count();                     // FIFO entries available, () → count
    uint8_t src = accel.read_interrupt_source();            // Read interrupt source, () → bitmask

    accel.self_test(false);                                 // Toggle self-test, (enabled) → None
    accel.set_sleep(false);                                 // Set sleep mode, (enabled, wakeup_hz=8) → Hz
    accel.set_link_mode(false);                             // Set activity/inactivity link, (enabled) → None
    accel.set_auto_sleep(false);                            // Set auto-sleep, (enabled) → None

    printf("fifo_count=%u interrupts=0x%02X\n", count, src);
    (void)n;
}