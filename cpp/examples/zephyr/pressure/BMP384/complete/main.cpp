#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP384.h"

#ifndef BMP384_I2C_NODE
#define BMP384_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP384_ADDR
#define BMP384_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP384_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP384_ADDR);
    BMP384Full bmp(connection);                             // Create BMP384 driver, (connection, spi=false)

    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
                                                            // sets oversampling, IIR coefficient, and output data rate
    bmp.set_mode(BMP384Full::MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None
    bool ready = bmp.is_data_ready();                       // Check data-ready flag, () → bool
                                                            // true if STATUS.drdy_press is set
    float t = bmp.temperature();                            // Read temperature, () → float °C
    float p = bmp.pressure();                               // Read pressure, () → float hPa
    float tp = 0.0f, tt = 0.0f;
    bmp.read(tp, tt);                                       // Read both values in one burst, (pressure_hpa, temperature_c) → None
    float fp = 0.0f, ft = 0.0f;
    bmp.read_forced(fp, ft);                                // Trigger forced measurement and read, (pressure_hpa, temperature_c) → None
    bmp.fifo_configure(true, true, 64);                     // Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=false) → None
                                                            // enables FIFO, sets watermark, arms pressure+temperature frames
    const char* types[16];
    double values[16];
    size_t n = bmp.fifo_read(types, values, 16);            // Read and parse FIFO frames, (types, values, max_frames) → size_t
    bmp.fifo_flush();                                       // Flush FIFO contents, () → None
    float alt = bmp.altitude();                             // Compute altitude, (sea_level_hpa=1013.25) → float m
    bmp.softreset();                                        // Soft reset chip, () → None

    check_true(t > -40.0f && t < 85.0f, "temperature_in_range");
    check_true(p > 300.0f && p < 1250.0f, "pressure_in_range");
    printk("T=%.1f C, P=%.1f hPa, ready=%d, frames=%d, alt=%.1f m\n",
        t, p, ready, (int)n, alt);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
