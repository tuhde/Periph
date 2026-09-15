#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/LPS28DFW.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TEST_ADDR);
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
    Serial.print("chip=0x"); Serial.print(cid, HEX);
    Serial.print(", ready="); Serial.print(ready);
    Serial.print(", p="); Serial.print(p, 2);
    Serial.print(", t="); Serial.print(t, 2);
    Serial.print(", alt="); Serial.print(alt, 1);
    Serial.print(", level="); Serial.println(level);
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }