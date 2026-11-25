#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "LIS3DH.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

I2CConnectionLinux connection(TEST_I2C_BUS, 0x18);
LIS3DHFull accel(connection);                        // Create LIS3DH driver, (connection)
static void on_int(uint16_t status) {
    printf("interrupt status: 0x%04X\n", status);
}
int main(void) {
    // --- Range, mode, data rate, axes ---
    accel.set_range(8);                                      // Set full-scale range, (range_g) → g
                                                            // reprograms FS in CTRL_REG4; sensitivity becomes 4 mg/LSB
    accel.set_mode(1);                                       // Set operating mode, (mode) → None
                                                            // 12-bit high-resolution → 10-bit normal (LPen=0, HR=0)
    accel.set_data_rate(400);                                // Set output data rate, (rate_hz) → Hz
                                                            // ODR = 400 Hz; waits one ODR period for the converter
    accel.set_axes(true, true, true);                        // Enable measurement axes, (x, y, z) → None
                                                            // Xen/Yen/Zen all set in CTRL_REG1

    // --- Power cycle and restore the defaults ---
    accel.power_down();                                      // Stop conversions, () → None
                                                            // ODR = 0000; the control registers are retained
    accel.set_data_rate(100);                                // Set output data rate, (rate_hz) → Hz
                                                            // leave power-down again at 100 Hz
    accel.set_mode(2);                                       // Set operating mode, (mode) → None
                                                            // back to high-resolution 12-bit mode
    accel.set_range(2);                                      // Set full-scale range, (range_g) → g
                                                            // back to ±2 g (1 mg/LSB)

    // --- Reads ---
    float x, y, z;
    accel.read(x, y, z);                                     // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                            // 6-byte burst of 0x28–0x2D, scaled to g
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                              // Read raw counts, (x, y, z) → int, int, int
                                                            // signed counts at the current resolution (12-bit here)
    bool ready = accel.new_data_available();                 // Check new data, () → bool
                                                            // STATUS_REG.ZYXDA — a fresh sample set is ready
    uint8_t status = accel.read_status();                    // Read STATUS register, () → int
                                                            // overrun and data-available flags

    // --- Auxiliary ADC and temperature ---
    accel.enable_adc(true);                                  // Enable auxiliary ADC, (enabled) → None
                                                            // sets ADC_EN in TEMP_CFG_REG
    float v1 = accel.read_adc(1);                            // Read aux ADC channel, (channel) → V
                                                            // 10-bit conversion mapped onto the ±400 mV input range
    float v2 = accel.read_adc(2);                            // Read aux ADC channel, (channel) → V
                                                            // second auxiliary input
    float v3 = accel.read_adc(3);                            // Read aux ADC channel, (channel) → V
                                                            // temperature input while TEMP_EN = 0
    accel.enable_adc(false);                                 // Disable auxiliary ADC, (enabled) → None
                                                            // clears ADC_EN again
    int16_t temp = accel.read_temperature_delta();           // Read relative temperature, () → int °C
                                                            // enables ADC_EN + TEMP_EN on first use, 1 digit/°C

    // --- High-pass filter ---
    accel.set_high_pass(1, 2, true, true, false, true);      // Configure high-pass filter, (mode, cutoff, filter_output, on_ia1, on_ia2, on_click) → None
                                                            // HPM=01, HPCF=10, filtered data routed to output, IA1 and click
    uint8_t ref = accel.reset_high_pass();                   // Reset high-pass filter, () → int
                                                            // reading REFERENCE resets the filter in HPM = 00
    accel.set_reference(0x40);                               // Set high-pass reference, (value) → None
                                                            // REFERENCE = 0x40 for HPM = 01

    // --- FIFO ---
    accel.set_fifo(LIS3DHFull::FIFO_FIFO, 16, false);        // Configure FIFO, (mode, watermark, trigger_int2) → None
                                                            // FIFO mode with a 16-sample watermark; FIFO_EN set
    uint8_t level = accel.fifo_level();                      // Read FIFO level, () → int
                                                            // FSS<4:0> — unread sample sets in the buffer
    bool wm, ov, empty;
    uint8_t lvl;
    accel.read_fifo_status(wm, ov, empty, lvl);              // Read FIFO status, () → (bool, bool, bool, int)
                                                            // WTM / OVRN_FIFO / EMPTY flags plus the level
    float xb[8], yb[8], zb[8];
    uint8_t n = accel.read_fifo(xb, yb, zb, 8);              // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) → int
                                                            // one 6-byte burst per sample set, oldest first
    accel.set_fifo(LIS3DHFull::FIFO_BYPASS);                 // Configure FIFO, (mode, watermark, trigger_int2) → None
                                                            // back to bypass, which empties the buffer

    // --- Inertial interrupt generators ---
    accel.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);    // Configure motion interrupt, (generator, events, threshold_g, duration_ms, and_mode) → None
                                                            // generator 1, all six axis events, 0.5 g, 30 ms, AND-combined
    accel.set_free_fall(1, 0.35f, 30);                       // Configure free-fall, (generator, threshold_g, duration_ms) → None
                                                            // INT1_CFG = 0x95: all axes low, AND mode
    accel.set_orientation(1, false, false);                  // Configure orientation, (generator, position, four_d) → None
                                                            // 6D movement recognition (a one-ODR pulse)
    accel.set_latch(1, true);                                // Latch generator 1, (generator, enabled) → None
                                                            // the interrupt stays high until INT1_SRC is read
    uint8_t src1 = accel.read_interrupt_source(1);          // Read interrupt source, (generator) → int
                                                            // INT1_SRC — reading it clears the interrupt
    accel.set_latch(1, false);                               // Latch generator 1, (generator, enabled) → None
                                                            // back to a self-resetting interrupt

    // --- Click detection ---
    accel.set_click(0x01, 0x04, 0.5f, 10, 20, 50, false);    // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → None
                                                            // single click on X, double click on Z
    uint8_t click = accel.read_click_source();               // Read click source, () → int
                                                            // CLICK_SRC — reading it clears the click interrupt

    // --- Sleep-to-wake activity ---
    accel.set_activity(0.5f, 100);                           // Configure activity, (threshold_g, duration_ms) → None
                                                            // ACT_THS / ACT_DUR; pair with SOURCE_ACTIVITY

    // --- Interrupt routing and delivery ---
    accel.set_interrupt_polarity(false);                     // Set interrupt polarity, (active_low) → None
                                                            // INT1 and INT2 stay active-high (the reset default)
    accel.enable_interrupt(LIS3DHFull::SOURCE_DATA_READY, 1);  // Enable interrupt source, (source, pin) → None
                                                            // routes the new-data event to INT1
    accel.enable_interrupt(LIS3DHFull::SOURCE_CLICK, 1);    // Enable interrupt source, (source, pin) → None
                                                            // routes the click event to INT1 too
    accel.disable_interrupt(LIS3DHFull::SOURCE_DATA_READY, 1);  // Disable interrupt source, (source, pin) → None
                                                            // removes the new-data event from INT1

    accel.on_interrupt(on_int, 1);                           // Subscribe to INT1, (callback, pin) → None
                                                            // the callback receives the combined status int
    uint16_t isr = accel.poll_interrupt();                   // Poll interrupt status, () → int
                                                            // reads the source registers of every enabled source
    accel.off_interrupt(1);                                  // Unsubscribe from INT1, (pin) → None
                                                            // removes this chip's handler from the INT pin

    // --- System ---
    accel.set_sdo_pullup(true);                              // Set SDO pull-up, (connected) → None
                                                            // reconnects the pull-up on SDO/SA0
    accel.reboot();                                          // Reboot memory content, () → None
                                                            // BOOT = 1, then the Initialization Sequence runs again
    bool st = accel.self_test(1);                            // Run self-test, (mode) → bool
                                                            // electrostatic self-test 0; true when every axis shows 17-360 LSb
    printf("self-test: %s\n", st ? "PASS" : "FAIL");
    printf("accel: x=%.3f y=%.3f z=%.3f g\n", x, y, z);
    printf("raw: %d %d %d LSB\n", rx, ry, rz);
    printf("ready=%d status=0x%02X\n", ready ? 1 : 0, status);
    printf("adc: %.3f %.3f %.3f V  temp=%d C\n", v1, v2, v3, temp);
    printf("reference=0x%02X\n", ref);
    printf("fifo: level=%d wm=%d ov=%d empty=%d read=%d\n",
           level, wm ? 1 : 0, ov ? 1 : 0, empty ? 1 : 0, n);
    printf("int1_src=0x%02X click_src=0x%02X isr=0x%04X\n", src1, click, isr);
    return 0;
}
