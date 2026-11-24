#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x70);
SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Soft reset and ID check, () → bool
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // normal mode, both CRCs verified, one retry on a bad CRC
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C humidity "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println(); // Read humidity only, () → float %RH
                                                                    // NAN on failure
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println(); // Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    Serial.print("raw T "); Serial.print((unsigned long)(t_raw)); Serial.print(" raw RH "); Serial.print((unsigned long)(rh_raw)); Serial.println();
    ok = sensor.read_id(id);                                        // Read the ID word, (id&) → bool
                                                                    // (id & 0x083F) == 0x0807 for the SHTC3
    Serial.print("id "); Serial.print((unsigned)(id), HEX); Serial.println();
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
                                                                    // 0.7 ms measurement, slightly noisier
    ok = sensor.read(t, rh);
    Serial.print("low power "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.set_low_power(false);
    sensor.set_clock_stretching(true);                              // Select clock stretching, (on) → void
                                                                    // needs a controller that tolerates SCL held low
    ok = sensor.read(t, rh);
    sensor.set_clock_stretching(false);
    sensor.set_sleep_between(false);                                // Sleep after every read?, (on) → void
                                                                    // off keeps the sensor idle (higher supply current)
    ok = sensor.read(t, rh);
    sensor.set_sleep_between(true);
    sensor.wake();                                                  // Wake-up command, () → void
    sensor.sleep();                                                 // Sleep command, () → void
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println(); // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    sensor.soft_reset();                                            // Soft reset, () → void
                                                                    // 0x805D, leaves the sensor idle
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
