#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMA150.h"

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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMA150Full accel(connection);                          // Create BMA150 Full driver, (connection)

    accel.set_range(8);                                     // Set measurement range, (range_g) → g
                                                             // selects ±8 g; LSB scale changes from 256 to 64 LSB/g
    accel.set_bandwidth(190);                               // Set bandwidth, (bandwidth_hz) → Hz
                                                             // picks nearest valid value (190 Hz)
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                             // Read raw 10-bit counts, (x, y, z) → int, int, int
                                                             // signed 10-bit two's-complement acceleration counts
    float temp = accel.read_temperature();                  // Read temperature, () → °C
                                                             // 0.5 °C/LSB, 0x00 maps to −30 °C
    accel.set_shadow(false);                                // Set shadow mode, (enabled) → None
                                                             // keep LSB-then-MSB ordering (shadow_dis=0)
    accel.set_low_g(0.4, 40);                               // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
                                                             // 0.4 g threshold, 40 ms duration; enables SOURCE_LOW_G
    accel.set_high_g(4.0, 2);                               // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
                                                             // 4.0 g threshold, 2 ms duration; enables SOURCE_HIGH_G
    accel.set_any_motion(0.5, 3);                           // Configure any-motion, (threshold_g, samples=1) → g, samples
                                                             // 0.5 g threshold, 3 consecutive samples; enables SOURCE_ANY_MOTION
    accel.set_alert(false);                                 // Toggle alert mode, (enabled) → None
                                                             // mutually exclusive with any-motion; not used here
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None
                                                             // latched until clear_interrupt(); latch_INT=1
    uint8_t status = accel.poll_interrupt();                // Read STATUS, () → bitmask
                                                             // STATUS byte; does not clear latched bits
    accel.clear_interrupt();                                // Clear latched interrupts, () → None
                                                             // writes reset_INT to CTRL (cleared on next sample)
    accel.set_wake_up(true, 80);                            // Set self-wake-up, (enabled, pause_ms=20) → ms
                                                             // 80 ms sleep portion of the cycle

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                             // burst read of 0x02–0x07, scale 64 LSB/g
    uint8_t al, ml;
    accel.read_version(al, ml);                             // Read version, (al_version, ml_version) → version, version
    uint8_t c1 = accel.read_customer(0);                    // Read scratch byte, (index) → byte
    accel.write_customer(0, 0xA5);                          // Write scratch byte, (index, value) → None
    bool st = accel.self_test();                            // Run self-test, () → bool
    accel.soft_reset();                                     // Soft reset, () → None
                                                             // CTRL.soft_reset=1; 30 ms wait; range/bandwidth restored
    accel.sleep();                                          // Enter sleep mode, () → None
    accel.wake();                                           // Leave sleep mode, () → None

    printf("raw=(%d,%d,%d) temp=%.1f status=0x%02X al=%u ml=%u c1=0x%02X st=%s\n",
           (int)rx, (int)ry, (int)rz, (double)temp, status, al, ml, c1, st ? "PASS" : "FAIL");
}
