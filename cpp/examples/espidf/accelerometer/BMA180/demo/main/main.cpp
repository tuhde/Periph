#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMA180.h"

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
    dev_cfg.device_address = 0x40;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz
    // --- Calibrate zero-g while the board sits level ---
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None
    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    accel.set_tap(0.5, 250);                               // Configure tap, (threshold_g, window_ms) → None
    accel.set_low_g(0.3, 40);                              // Configure low-g, (threshold_g, duration_ms) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    for (int i = 0; i < 600; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        float t   = accel.read_temperature();               // Read temperature, () → °C
        printf("|a|=%.3f g  T=%.1f C\n", (double)mag, (double)t);

        uint8_t flags = accel.poll_interrupt();            // Read STATUS_REG3, () → bitmask
        if (flags & 0x10) {
            printf("DOUBLE TAP\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        if (flags & 0x40) {
            printf("FREE FALL\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}