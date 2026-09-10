#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/LPS22DF.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x5C);

    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    LPS22DFFull lps(connection);                           // Create LPS22DF driver, (connection, spi=false)
    lps.configure(4, 0, true, 1, true);                    // Configure chip, (odr=25 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → None

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    // LPS22DF reports absolute pressure; relative altitude is what matters indoors.
    delay(2000);
    float baseline_p = lps.pressure();                      // Read pressure, () → float Pa
    Serial.print("Baseline: ");
    Serial.print(baseline_p, 0);
    Serial.println(" Pa");

    float pressures[30], temps[30], deltas[30];
    for (int n = 0; n < 30; n++) {
        float p = lps.pressure();                          // Read pressure, () → float Pa
        float t = lps.temperature();                       // Read temperature, () → float °C
        float d = lps.altitude(baseline_p);                // Compute altitude, (sea_level_pa=baseline_p) → float m
                                                          // delta altitude in metres from the baseline
        pressures[n] = p;
        temps[n] = t;
        deltas[n] = d;
        Serial.print(n); Serial.print("s: ");
        Serial.print(p, 0); Serial.print(" Pa, T=");
        Serial.print(t, 2); Serial.print(" C, dalt=");
        Serial.print(d, 3); Serial.println(" m");
        delay(1000);
    }

    float pmin = pressures[0], pmax = pressures[0], psum = 0;
    float tmin = temps[0], tmax = temps[0], tsum = 0;
    float dmin = deltas[0], dmax = deltas[0], dsum = 0;
    for (int i = 0; i < 30; i++) {
        if (pressures[i] < pmin) pmin = pressures[i];
        if (pressures[i] > pmax) pmax = pressures[i];
        psum += pressures[i];
        if (temps[i] < tmin) tmin = temps[i];
        if (temps[i] > tmax) tmax = temps[i];
        tsum += temps[i];
        if (deltas[i] < dmin) dmin = deltas[i];
        if (deltas[i] > dmax) dmax = deltas[i];
        dsum += deltas[i];
    }
    Serial.print("P min="); Serial.print(pmin, 0);
    Serial.print(" max="); Serial.print(pmax, 0);
    Serial.print(" mean="); Serial.print(psum / 30, 1);
    Serial.println(" Pa");
    Serial.print("T min="); Serial.print(tmin, 2);
    Serial.print(" max="); Serial.print(tmax, 2);
    Serial.print(" mean="); Serial.print(tsum / 30, 2);
    Serial.println(" C");
    Serial.print("dalt min="); Serial.print(dmin, 3);
    Serial.print(" max="); Serial.print(dmax, 3);
    Serial.print(" mean="); Serial.print(dsum / 30, 3);
    Serial.println(" m");
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }