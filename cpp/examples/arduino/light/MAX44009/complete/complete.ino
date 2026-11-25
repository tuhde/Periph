#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, MAX44009Full::I2C_ADDRESS);
MAX44009Full sensor(connection);                        // Create MAX44009 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    // 2^E × M × 0.045; 188006.4 on overrange
    Serial.print("lux "); Serial.println((double)lx, 2);
    uint8_t e = 0, m = 0;
    bool over = sensor.readRaw(e, m);                   // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    Serial.print("E="); Serial.print((int)e); Serial.print(" M="); Serial.print((int)m); Serial.print(" overrange="); Serial.println((int)over);
    bool last = sensor.isOverrange();                   // Query the last reading, () → bool
    // true if the last exponent field was 15
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    // 2^E × 0.045 for the last reading
    Serial.print("overrange "); Serial.print((int)last); Serial.print(" resolution "); Serial.println((double)res, 3);
    sensor.setContinuous(true);                         // Select measurement mode, (continuous bool) → void
    // true: a new measurement as soon as one finishes
    sensor.setManual(100.0f, false);                    // Select manual range, (integrationMs, divideBy8=false) → void
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    // {continuous, manual, divideBy8, integrationMs}
    Serial.print("manual "); Serial.print((int)cfg.manual); Serial.print(" integration "); Serial.print((double)cfg.integrationMs, 2); Serial.println(" ms");
    sensor.setAutomatic();                              // Select automatic range, () → void
    // the chip chooses the current divider and integration time
    sensor.setContinuous(false);                        // Back to one measurement per 800 ms, (continuous bool) → void
    bool ok = sensor.setThresholds(50.0f, 500.0f);      // Set the interrupt window, (lowLux, highLux) → bool
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    float lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                       // Read the window, (lowLux&, highLux&) → void
    Serial.print("window "); Serial.print((double)lo, 2); Serial.print(" .. "); Serial.print((double)hi, 2); Serial.print(" lx (set "); Serial.print((int)ok); Serial.println(")");
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    // 0–25.5 s in 0.1 s steps
    float delay = sensor.getThresholdDelay();           // Read the delay, () → float s
    Serial.print("delay "); Serial.print((double)delay, 1); Serial.println(" s");
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    // INT_ENABLE = 1
    sensor.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    uint8_t status = sensor.pollInterrupt();            // Read and clear INT_STATUS, () → uint8_t
    // bit 0 = SOURCE_THRESHOLD
    Serial.print("status "); Serial.println((int)status);
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    // INT_ENABLE = 0; also clears a pending status
}

void loop() {
}
