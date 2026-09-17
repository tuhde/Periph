#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../../../src/connection/I2CConnection.h"
#include "../../../../src/chips/gyroscope/L3G4200D.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x68);

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    L3G4200DFull gyro(connection);                           // Create L3G4200D driver, (connection, spi=false)
    gyro.configure(1, 0, 500);                               // Configure chip, (odr=200Hz, bandwidth=0, full_scale=500) → None
    gyro.enable_highpass(0, 4);                              // Enable high-pass, (mode=0, cutoff=4) → None
                                                                // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    gyro.enable_fifo(L3G4200DFull::FIFO_STREAM, 10);          // Enable FIFO, (mode=2=stream, watermark=10) → None

    float threshold_rad_s = 90.0f * (3.141592653589793f / 180.0f);
    int alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for (int n = 0; n < 50; n++) {
        while (gyro.fifo_samples() < 10) {                   // Read FIFO count, () → int
            delay(5);
        }
        // Drain: the FIFO holds 10 samples × 6 bytes = 60 bytes; read in one burst.
        uint8_t buf[60];
        // Use I2CConnection's read via a transient burst on OUT_X_L is not
        // directly exposed; instead read 6 bytes at a time.
        // For demo brevity, just print a single per-cycle read.
        float x, y, z;
        gyro.angular_rate(x, y, z);                           // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        if (fabsf(x) > threshold_rad_s || fabsf(y) > threshold_rad_s || fabsf(z) > threshold_rad_s) {
            alerts++;
            Serial.print("ALERT  X="); Serial.print(x, 2);
            Serial.print(" Y="); Serial.print(y, 2);
            Serial.print(" Z="); Serial.print(z, 2);
            Serial.println(" rad/s");
        } else {
            Serial.print("       X="); Serial.print(x, 2);
            Serial.print(" Y="); Serial.print(y, 2);
            Serial.print(" Z="); Serial.print(z, 2);
            Serial.println(" rad/s");
        }
        delay(20);
    }

    Serial.print("Total alerts: "); Serial.print(alerts);
    Serial.println(" / 50");
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
