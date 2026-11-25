#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                  // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; highest resolution, heater off
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0;
    uint8_t osr_rh = 0, osr_t = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // OSR 3 / 3 by default, both CRCs verified
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C humidity "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println();// Read humidity only, () → float %RH
                                                                    // command 0x10; NAN on failure
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println();// Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    Serial.print("raw T "); Serial.print((unsigned long)(t_raw)); Serial.print(" raw RH "); Serial.print((unsigned long)(rh_raw)); Serial.println();
    ok = sensor.set_resolution(1, 2);                               // Set oversampling, (osr_rh, osr_t) → bool
                                                                    // 0–3 each; higher OSR = finer resolution, longer conversion
    sensor.get_resolution(osr_rh, osr_t);                           // Get oversampling, (osr_rh&, osr_t&) → void
    Serial.print("osr rh "); Serial.print((unsigned long)(osr_rh)); Serial.print(" osr t "); Serial.print((unsigned long)(osr_t)); Serial.println();
    Serial.print("conversion "); Serial.print(sensor.conversion_time() * 1000.0f, 1); Serial.print(" ms"); Serial.println();// Typical conversion time, () → float s
    sensor.set_resolution(3, 3);
    sensor.start_conversion();                                      // Start a conversion, () → void
                                                                    // non-blocking; wait conversion_time() before reading
    delay(30);
    ok = sensor.read_result(t, rh);                                 // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    Serial.print("result "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
                                                                    // 0x04; readings drift while the heater runs
    Serial.print("heater on "); Serial.print((unsigned long)(sensor.is_heater_on())); Serial.println();// Heater state, () → bool
    sensor.set_heater(false);                                       // Heater off
    Serial.print("diagnostic 0x"); Serial.print((unsigned)(sensor.diagnostic()), HEX); Serial.println();// Read the diagnostic byte, () → uint8_t
                                                                    // test against HTU31DFull::DIAG_* masks
    uint32_t serial = 0;
    ok = sensor.serial_number(serial);                              // Read the serial number, (serial&) → bool
                                                                    // 24-bit, CRC verified
    Serial.print("serial 0x"); Serial.print((unsigned)(serial), HEX); Serial.println();
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();// Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula; NAN on failure
    sensor.software_reset();                                        // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
