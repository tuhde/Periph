#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADXL345.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x53;
    I2CConnectionLinux connection(bus, addr);

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

    (void)n;
    printf("fifo_count=%u interrupts=0x%02X\n", count, src);
    return 0;
}