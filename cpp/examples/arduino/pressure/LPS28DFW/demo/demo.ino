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

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    LPS28DFWFull lps(connection);                               // Create LPS28DFW driver, (connection)
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=True, lpf_cfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    int samples = 0;
    for (int n = 0; n < 60; n++) {
        float p = 0.0f, t = 0.0f;
        lps.read(p, t);                                         // Read both values, (pressure, temperature) → void
        float alt = 44330.0f * (1.0f - powf(p / 1013.25f, 1.0f / 5.255f));
        float elapsed = (n + 1) * 0.5f;
        Serial.print(elapsed, 1);
        Serial.print("s  ");
        Serial.print(p, 2); Serial.print(" hPa  ");
        Serial.print(t, 2); Serial.print(" C  ");
        Serial.print(alt, 1); Serial.println(" m");
        samples++;
        delay(500);
    }
    Serial.print("Total samples: "); Serial.println(samples);
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }