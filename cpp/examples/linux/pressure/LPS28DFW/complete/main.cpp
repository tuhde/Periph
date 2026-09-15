#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <cstdio>
#include "I2CConnectionLinux.h"
#include "LPS28DFW.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
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
    float t = lps.read_temperature();                           // Read temperature, () → float °C
    float p = lps.read_pressure();                              // Read pressure, () → float hPa
    float bp = 0.0f, bt = 0.0f;
    lps.read(bp, bt);                                           // Read both values, (pressure, temperature) → void
                                                                // burst-reads pressure+temperature
    lps.softreset();                                            // Soft reset, () → void
                                                                // waits ~2 ms for reboot
    lps.fifo_configure(LPS28DFWFull::FIFO_FIFO, 16, 1);         // Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm 0/1) → void
                                                                // enables 16-sample watermark FIFO
    uint8_t level = lps.fifo_level();                           // FIFO unread count, () → uint8_t
    float samples[128] = {0};
    lps.fifo_read(level, samples);                              // Drain FIFO, (count, buf) → void
    float op = 0.0f, ot = 0.0f;
    lps.read_oneshot(op, ot);                                   // One-shot read, (pressure, temperature) → void
                                                                // triggers a single measurement with ODR=0
    float alt = lps.altitude();                                 // Compute altitude, (sea_level_hpa=1013.25) → float m

    check_true(t > -40.0f && t < 85.0f, "temperature_in_range");
    check_true(p > 260.0f && p < 4060.0f, "pressure_in_range");
    printf("chip=0x%02X, ready=%d, T=%.1f C, P=%.1f hPa, alt=%.1f m, level=%d\n",
        cid, ready, t, p, alt, level);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
