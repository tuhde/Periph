#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/Lps33hw.h"

static int passed = 0, failed = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x5C);

    // --- Initialization and configuration for altimeter preset ---
    // ODR=10 Hz gives ~10 Hz pressure output; BDU=1 latches the output
    // registers so a coherent 24-bit pressure can be read without tearing;
    // EN_LPFP=1 with LPFP_BW_ODR_20 (LPFP_CFG=1) gives an additional
    // ODR/20 low-pass filter that suppresses the kind of cabin-air
    // pressure bursts that would otherwise read as bogus altitude steps.
    LPS33HWFull lps(connection);                           // Create LPS33HW driver, (connection)
    lps.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr=10Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → None
    lps.reset_lpf();                                      // Flush transitory LPF state after enabling EN_LPFP, () → None

    // --- Main loop: poll P_DA rather than fixed delay ---
    // The chip updates pressure asynchronously at 10 Hz; spinning on the
    // STATUS register's P_DA bit lets us sample fresh data immediately
    // rather than racing the ODR clock with delay().
    const float sea_level_Pa = 101325.0f;
    unsigned long last_print = 0;
    unsigned long last_autozero = 0;
    unsigned long t0 = millis();

    while (millis() - t0 < 60000UL) {
        float p_Pa = lps.pressure();                      // Read pressure, () → float Pa
                                                        // waits for STATUS.P_DA before reading PRESS_XL..PRESS_H
        float t_C = lps.temperature();                    // Read temperature, () → float °C
        unsigned long now = millis();

        if (now - last_print >= 1000UL) {
            last_print = now;
            // --- Altitude via the barometric formula ---
            // The 44330 × (1 − (p/p0)^(1/5.255)) approximation is valid up
            // to ~11000 m and troposphere temperatures; for higher
            // altitudes use the full hypsometric equation.
            float altitude_m = 44330.0f * (1.0f - powf(p_Pa / sea_level_Pa, 1.0f / 5.255f));  // Barometric altitude, () → float m
            Serial.print(millis() / 1000);
            Serial.print("s: alt=");
            Serial.print(altitude_m, 2);
            Serial.print(" m, T=");
            Serial.print(t_C, 2);
            Serial.println(" C");
        }

        // --- AUTOZERO removes atmospheric drift every 10 s ---
        // Weather fronts shift sea-level pressure by ~1 hPa/hour, which
        // would otherwise show up as bogus altitude drift in a relative
        // (uncalibrated) altimeter; re-zeroing REF_P every 10 s cancels
        // that slow DC bias without throwing away the 10 Hz rate.
        if (now - last_autozero >= 10000UL) {
            last_autozero = now;
            lps.set_autozero();                           // Set AUTOZERO, () → None
                                                        // current pressure is stored in REF_P
            Serial.println("Reference updated.");
        }
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }