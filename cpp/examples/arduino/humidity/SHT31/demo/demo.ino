#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x44);
SHT31Full sensor(connection);                                           // Create SHT31 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.soft_reset();                                                // Soft reset, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    Serial.print("status 0x"); Serial.print((unsigned)status, HEX); Serial.println();
    // --- Single shots at all three repeatability levels ---
    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);                                // Select repeatability, (level) → bool
        uint32_t t0 = millis();
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        uint32_t dt = millis() - t0;
        Serial.print("level "); Serial.print((unsigned)level); Serial.print(": "); Serial.print(t, 3); Serial.print(" C "); Serial.print(rh, 3); Serial.print(" %RH ("); Serial.print((unsigned long)dt); Serial.print(" ms)"); Serial.println();
    }
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
    Serial.print("raw "); Serial.print((unsigned)raw_t); Serial.print(" "); Serial.print((unsigned)raw_rh); Serial.print(", T "); Serial.print(sensor.read_temperature(), 3); Serial.print(", RH "); Serial.print(sensor.read_humidity(), 3); Serial.println();

    // --- Periodic mode at 2 mps: ten samples ---
    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
    for (uint8_t got = 0; got < 10;) {
        if (!sensor.fetch(t, rh)) {                                     // Fetch the latest periodic result, (t, rh) → bool
            delay(50);
            continue;
        }
        got++;
        Serial.print("periodic "); Serial.print((unsigned)got); Serial.print(": "); Serial.print(t, 3); Serial.print(" C "); Serial.print(rh, 3); Serial.print(" %RH"); Serial.println();
    }
    sensor.stop_periodic();                                             // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    float before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    delay(10000);
    sensor.read_status(status);                                         // Read status register, (status) → bool
    Serial.print("heater status bit: "); Serial.print((unsigned)((status >> 13) & 1)); Serial.println();
    Serial.print("temperature rise: "); Serial.print(sensor.read_temperature() - before, 3); Serial.print(" K"); Serial.println();
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    Serial.print("status 0x"); Serial.print((unsigned)status, HEX); Serial.print(", dew point "); Serial.print(sensor.dew_point(), 3); Serial.print(" C"); Serial.println();
}

void loop() {
    delay(1000);
}
