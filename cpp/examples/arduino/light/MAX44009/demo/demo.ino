#include <math.h>
#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, MAX44009Full::I2C_ADDRESS);
MAX44009Full sensor(connection);                        // Create MAX44009 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    // --- Configuration and first reading ---
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    Serial.print("manual "); Serial.print((int)cfg.manual); Serial.print(", lux "); Serial.print((double)lx, 2); Serial.print(", resolution "); Serial.print((double)res, 3); Serial.println(" lx/LSB");

    // --- Arm a window of +-30 % around the current illuminance ---
    // The captureless callback only records the status; the loop below reacts to it.
    static volatile uint8_t pending = 0;
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    sensor.setThresholds(lx * 0.7f, lx * 1.3f);         // Set the interrupt window, (lowLux, highLux) → bool
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    sensor.onInterrupt([](uint8_t status) { pending = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Run for 60 s: react to interrupts, print the raw reading once a second ---
    for (int s = 0; s < 60; s++) {
        sensor.service();                               // Poll once and dispatch the callback, () → void
        if (pending) {
            pending = 0;
            float now = sensor.lux();                   // Read illuminance, () → float lux
            float level = now < 0.1f ? 0.1f : (now > 10000.0f ? 10000.0f : now);
            float backlight = 10.0f + 90.0f * (log10f(level) + 1.0f) / 5.0f;
            Serial.print("lux "); Serial.print((double)now, 2); Serial.print(" -> backlight "); Serial.print((double)backlight, 0); Serial.println(" %");
            sensor.setThresholds(now * 0.7f, now * 1.3f);  // Set the interrupt window, (lowLux, highLux) → bool
        }
        uint8_t e = 0, m = 0;
        sensor.readRaw(e, m);                           // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
        Serial.print("E="); Serial.print((int)e); Serial.print(" M="); Serial.print((int)m); Serial.print(" overrange="); Serial.println((int)sensor.isOverrange());
        delay(1000);
    }
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
}

void loop() {
}
