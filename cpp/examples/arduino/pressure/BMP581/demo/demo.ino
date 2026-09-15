#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../../src/connection/I2CConnection.h"
#include "../../../src/chips/pressure/BMP581.h"

static int passed = 0, failed = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x46);

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    // 10 Hz ODR (odr field 0x17) gives sub-decimetre altitude resolution over
    // a 30-second window while still leaving headroom for higher OSR.
    BMP581Full bmp(connection);                           // Create BMP581 driver, (connection, spi=false)
    bmp.configure(0x17, BMP581Full::OSR_16X, BMP581Full::OSR_4X, true);  // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → None

    float pressures[300], temps[300], alts[300];
    int count = 0;
    for (int n = 0; n < 300; n++) {
        float p = bmp.pressure();                         // Read pressure, () → float Pa
        float t = bmp.temperature();                      // Read temperature, () → float °C
        float a = bmp.altitude();                         // Compute altitude, (sea_level_pa=101325.0) → float m
        if (n % 10 == 0) {
            int start = (n >= 10) ? n - 10 : 0;
            int span = (n >= 10) ? 10 : n;
            float mp = 0, mt = 0, ma = 0;
            for (int k = start; k < n; k++) {
                mp += pressures[k];
                mt += temps[k];
                ma += alts[k];
            }
            if (span > 0) { mp /= span; mt /= span; ma /= span; }
            Serial.print(n / 10);
            Serial.print("0s: rolling P=");
            Serial.print(mp, 1);
            Serial.print(" Pa, T=");
            Serial.print(mt, 2);
            Serial.print(" C, alt=");
            Serial.print(ma, 2);
            Serial.println(" m");
        }
        pressures[n] = p;
        temps[n] = t;
        alts[n] = a;
        delay(100);
    }

    // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
    // Coefficient 3 = 7-tap filter; expect noticeably tighter altitude variance.
    float amin = alts[0], amax = alts[0];
    for (int n = 1; n < 300; n++) {
        if (alts[n] < amin) amin = alts[n];
        if (alts[n] > amax) amax = alts[n];
    }
    Serial.print("Bypass: alt min=");
    Serial.print(amin, 3);
    Serial.print(" max=");
    Serial.print(amax, 3);
    Serial.print(" spread=");
    Serial.print(amax - amin, 3);
    Serial.println(" m");

    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → None

    float alts2[300];
    for (int n = 0; n < 300; n++) {
        bmp.pressure();                                   // Read pressure, () → float Pa
        alts2[n] = bmp.altitude();                        // Compute altitude, (sea_level_pa=101325.0) → float m
        delay(100);
    }
    float amin2 = alts2[0], amax2 = alts2[0];
    for (int n = 1; n < 300; n++) {
        if (alts2[n] < amin2) amin2 = alts2[n];
        if (alts2[n] > amax2) amax2 = alts2[n];
    }
    Serial.print("IIR=3:  alt min=");
    Serial.print(amin2, 3);
    Serial.print(" max=");
    Serial.print(amax2, 3);
    Serial.print(" spread=");
    Serial.print(amax2 - amin2, 3);
    Serial.println(" m");

    float pmin = pressures[0], pmax = pressures[0], psum = 0;
    for (int n = 0; n < 300; n++) {
        if (pressures[n] < pmin) pmin = pressures[n];
        if (pressures[n] > pmax) pmax = pressures[n];
        psum += pressures[n];
    }
    Serial.print("Min P=");
    Serial.print(pmin, 1);
    Serial.print(", max P=");
    Serial.print(pmax, 1);
    Serial.print(", mean P=");
    Serial.print(psum / 300.0, 1);
    Serial.println(" Pa");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }