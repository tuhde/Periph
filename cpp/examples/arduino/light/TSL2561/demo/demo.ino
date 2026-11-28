#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

/* Light meter with alarm: auto-ranged lux every 2 s for 30 s, then a ±50 % channel-0
   window with persistence 2 reported while the sensor is covered. */
I2CConnection connection(Wire, TSL2561Full::I2C_ADDRESS);
TSL2561Full sensor(connection);                         // Create TSL2561 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    sensor.init();                                      // Power up, 1× gain, 402 ms, () → bool
    Serial.print("ID 0x"); Serial.println(sensor.readId(), HEX);  // Read the ID register, () → int

    /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
    uint16_t ch0 = 0, ch1 = 0;
    for (int i = 0; i < 15; i++) {
        float lx = sensor.autoRange();                  // Auto-range and read lux, () → float lux
        sensor.readRaw(ch0, ch1);                       // Read both channels, (ch0&, ch1&) → bool
        Serial.print((double)lx, 1); Serial.print(" lx  gain "); Serial.print(sensor.getGain());  // Current gain, () → uint8_t
        Serial.print("  "); Serial.print(sensor.getIntegrationTime());  // Current integration time, () → uint16_t ms
        Serial.print(" ms  ch0 "); Serial.print(ch0); Serial.print("  ch1 "); Serial.println(ch1);
        delay(2000);
    }

    /* Alarm: a window of ±50 % around the current channel-0 count. */
    sensor.readRaw(ch0, ch1);
    uint32_t high = (uint32_t)ch0 * 3 / 2;
    if (high > 65535) high = 65535;
    sensor.setThresholds(ch0 / 2, (uint16_t)high);      // Set channel-0 window, (low, high) → bool
    sensor.configureInterrupt(1, 2);                    // Level interrupt, 2 cycles outside, (mode, persist) → bool
    Serial.print("window "); Serial.print(ch0 / 2); Serial.print(" .. "); Serial.print((unsigned long)high);
    Serial.println(" - cover the sensor now");
    for (int i = 0; i < 10; i++) {
        Serial.print("outside window: "); Serial.println((int)sensor.pollInterrupt());  // Check ch0 against the window, () → bool
        delay(1000);
    }

    sensor.clearInterrupt();                            // Release the level interrupt, () → bool
    sensor.configureInterrupt(0, 0);
    sensor.powerDown();                                 // Power down, () → bool
    Serial.print("powered: "); Serial.println((int)sensor.isPowered());  // Power state, () → bool
}

void loop() {
}
