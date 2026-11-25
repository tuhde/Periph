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

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // single shot, high repeatability, CRC checked
    Serial.print(t, 3); Serial.print(" C, "); Serial.print(rh, 3); Serial.print(" %RH"); Serial.println();
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 3); Serial.print(" C"); Serial.println();
    // sensor.read_temperature() → float °C (NAN on failure)
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 3); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity() → float %RH (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
                                                                        // 16-bit temperature and humidity ticks
    Serial.print("raw "); Serial.print((unsigned)raw_t); Serial.print(" "); Serial.print((unsigned)raw_rh); Serial.println();

    sensor.set_repeatability(SHT31Full::REPEATABILITY_LOW);             // Select repeatability, (level) → bool
                                                                        // low / medium / high (4 / 6 / 15 ms)
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);            // Select repeatability, (level) → bool
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
                                                                        // rate 0.5 / 1 / 2 / 4 / 10 mps; art=true selects the 4 mps ART mode
    delay(1000);
    bool have = sensor.fetch(t, rh);                                    // Fetch the latest periodic result, (t, rh) → bool
                                                                        // false while the read header is NACKed
    Serial.print("fetch "); Serial.print((unsigned)have); Serial.print(": "); Serial.print(t, 3); Serial.print(" C "); Serial.print(rh, 3); Serial.print(" %RH"); Serial.println();
    sensor.stop_periodic();                                             // Stop periodic mode, () → void
                                                                        // break command 0x3093, waits 1 ms

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5-25 mW; readings are unusable while on
    sensor.read_status(status);                                         // Read status register, (status) → bool
                                                                        // CRC verified; bit 13 = heater on
    Serial.print("status 0x"); Serial.print((unsigned)status, HEX); Serial.println();
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
                                                                        // command 0x3041
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 3); Serial.print(" C"); Serial.println();
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0x30A2, waits 1 ms, back to single shot
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
