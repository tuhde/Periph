#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS22DF.h"

#ifndef LPS22DF_I2C_NODE
#define LPS22DF_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS22DF_ADDR
#define LPS22DF_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS22DF_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS22DF_ADDR);
    LPS22DFFull lps(connection);                              // Create LPS22DF driver, (connection, spi=false)
    lps.configure(3, 0, true, 1, true);                      // Configure chip, (odr=10 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → None
                                                                 // writes CTRL_REG1 and CTRL_REG2
    lps.oneshot();                                              // Trigger one-shot conversion, () → None
    float p = lps.pressure();                                  // Read pressure, () → float Pa
                                                                 // 24-bit two's complement, 4096 LSB/hPa → Pa
    float t = lps.temperature();                               // Read temperature, () → float °C
                                                                 // 16-bit two's complement, 100 LSB/°C
    float alt = lps.altitude(101325.0);                        // Compute altitude, (sea_level_pa=101325.0) → float m
                                                                 // barometric formula
    lps.software_reset();                                      // Reset chip, () → None
                                                                 // self-clears SWRESET bit after <5 µs
    lps.set_pressure_offset(-50.0);                            // Set pressure offset, (offset_pa=-50.0) → None
    lps.set_pressure_threshold(102000.0);                      // Set pressure threshold, (threshold_pa=102000.0) → None
    lps.configure_interrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (int_h_l, pp_od, drdy, drdy_pls, int_en, int_f_wtm, int_f_full, int_f_ovr) → None
    lps.configure_pressure_event(true, false, false);         // Configure pressure event, (phe=true, ple=false, lir=false) → None
    lps.autozero();                                            // Capture AUTOZERO reference, () → None
    lps.reset_reference();                                     // Reset reference, () → None
    float ref = lps.reference_pressure();                      // Read reference pressure, () → float Pa
    lps.set_fifo_mode(LPS22DFFull::FIFO_FIFO);                 // Set FIFO mode, (mode 0–5) → None
    lps.set_fifo_watermark(64);                                // Set FIFO watermark, (level 0–127) → None
    uint8_t count = lps.fifo_sample_count();                   // Read FIFO sample count, () → int
    float samples[128];
    uint8_t n_read = lps.read_fifo(samples, 128);              // Read FIFO samples, (out_buf, max_samples) → int
    uint8_t src = lps.interrupt_source();                      // Read interrupt source, () → int
    printk("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%u/%u, src=0x%02X\n",
           t, p, alt, ref, n_read, count, src);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}