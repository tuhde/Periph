#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMA180.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

    accel.set_range(8);                                     // Set measurement range, (range_g) → g
                                                             // selects ±8 g; LSB scale changes from 4096 to 1024 LSB/g
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz
                                                             // picks nearest low-pass value (40 Hz)
    accel.set_filter_mode(1);                               // Set filter mode, (mode) → None
                                                             // 1 = high-pass 1 Hz
    accel.set_mode(0);                                      // Set mode, (mode) → None
                                                             // mode_config = 00 (low-noise)
    accel.set_resolution(14);                               // Set resolution, (bits) → None
                                                             // 14-bit readout (readout_12bit cleared)
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                             // Read raw 14-bit counts, (x, y, z) → int, int, int
                                                             // signed 14-bit two's-complement acceleration counts
    float temp = accel.read_temperature();                  // Read temperature, () → °C
                                                             // 0.5 °C/LSB, 0x02 maps to 25 °C
    bool ready = accel.new_data_available();                // Check new data, () → bool
                                                             // True once all three new_data_X/Y/Z bits are set
    accel.set_shadow(false);                                // Set shadow mode, (enabled) → None
                                                             // keep LSB-then-MSB ordering (shadow_dis=0)
    accel.set_sample_skip(false);                           // Set sample skip, (enabled) → None
                                                             // smp_skip off (only useful with new-data interrupt)

    accel.set_low_g(0.3, 40, 0.05f, 0x07, 0, true);         // Configure low-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → None
                                                             // 0.3 g threshold, 40 ms duration, 0.05 g hysteresis, all axes, debounce 0, filtered
    accel.set_high_g(1.8f, 20, 0.1f, 0x07, 0, true);        // Configure high-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → None
                                                             // 1.8 g threshold, 20 ms duration, 0.1 g hysteresis, all axes
    accel.set_slope(0.3f, 3, 0x07, true);                   // Configure slope, (threshold_g, samples, axes, filtered) → None
                                                             // 0.3 g slope, 3 samples, all axes, filtered (exclusive with alert)
    accel.set_alert(false);                                 // Toggle alert mode, (enabled) → None
                                                             // slope_alert off
    accel.set_tap(0.5f, 250, 0x07, true);                   // Configure tap, (threshold_g, window_ms, axes, filtered) → None
                                                             // 0.5 g threshold, 250 ms window, all axes
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None
                                                             // latched until clear_interrupt(); lat_int=1

    uint8_t status = accel.poll_interrupt();                // Read STATUS_REG3, () → bitmask
                                                             // latched flag bits; does not clear
    accel.clear_interrupt();                                // Clear latched interrupts, () → None
                                                             // writes reset_INT to CTRL_REG0 (0x0D)
    accel.set_wake_up(true, 80);                            // Set self-wake-up, (enabled, pause_ms) → ms
                                                             // 80 ms sleep portion of the cycle

    float x, y, z;
    accel.read(x, y, z);                                   // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                             // burst read of 0x02..0x07, scale 1024 LSB/g
    uint8_t al, ml;
    accel.read_version(al, ml);                            // Read version, (al_version, ml_version) → version, version
                                                             // (al_version, ml_version) from VERSION register
    uint8_t c1 = accel.read_customer(0);                    // Read scratch byte, (index) → byte
                                                             // 0 -> CD1, 1 -> CD2
    accel.write_customer(1, 0xA5);                         // Write scratch byte, (index, value) → None
    bool st = accel.self_test();                           // Run self-test, () → bool
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None
                                                             // in-field zero-g calibration (volatile)
    accel.soft_reset();                                    // Soft reset, () → None
                                                             // writes 0xB6 to RESET (0x10); 30 ms wait; defaults restored
    accel.sleep();                                         // Enter sleep mode, () → None
    accel.wake();                                          // Leave sleep mode, () → None

    printf("raw=(%d,%d,%d) temp=%.1f ready=%d status=0x%02X al=%u ml=%u c1=0x%02X st=%s\n",
           (int)rx, (int)ry, (int)rz, (double)temp, (int)ready, status, al, ml, c1, st ? "PASS" : "FAIL");
    return 0;
}