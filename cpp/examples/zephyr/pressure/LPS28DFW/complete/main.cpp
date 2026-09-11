#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LPS28DFW.h"

#ifndef LPS28DFW_I2C_NODE
#define LPS28DFW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS28DFW_ADDR
#define LPS28DFW_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS28DFW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS28DFW_ADDR);
    LPS28DFWFull lps(connection);                               // Create LPS28DFW driver, (connection)
    uint8_t cid = lps.chip_id();                                // Read chip ID, () → uint8_t
                                                                // returns 0xB4 for LPS28DFW
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpf_en 0/1, lpf_cfg 0/1) → void
                                                                // sets output data rate, averaging, full-scale, IIR filter
    lps.set_threshold(1050.0, 1, 1);                            // Set pressure threshold, (threshold_hpa, high, low) → void
                                                                // arms PH/PL when pressure crosses threshold_hPa
    lps.set_offset(0.5);                                        // Set one-point calibration, (offset_hpa) → void
                                                                // subtracts 0.5 hPa from subsequent readings
    uint8_t ready = lps.is_data_ready();                        // Check data ready, () → uint8_t
                                                                // reads STATUS.P_DA
    float p = 0.0f, t = 0.0f;
    lps.read(p, t);                                             // Read both values, (pressure, temperature) → void
                                                                // burst-reads pressure+temperature
    lps.softreset();                                            // Soft reset, () → void
                                                                // waits ~2 ms for reboot
    lps.fifo_configure(LPS28DFWFull::FIFO_FIFO, 16, 1);         // Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm 0/1) → void
                                                                // enables 16-sample watermark FIFO
    uint8_t level = lps.fifo_level();                           // FIFO unread count, () → uint8_t
    float samples[128] = {0};
    lps.fifo_read(level, samples);                              // Drain FIFO, (count, buf) → void
    lps.read_oneshot(p, t);                                     // One-shot read, (pressure, temperature) → void
                                                                // triggers a single measurement with ODR=0
    float alt = lps.altitude();                                 // Compute altitude, (sea_level_hpa=1013.25) → float m
    printk("chip=0x%02X ready=%u p=%.2f t=%.2f alt=%.1f level=%u\n",
           cid, ready, (double)p, (double)t, (double)alt, level);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}