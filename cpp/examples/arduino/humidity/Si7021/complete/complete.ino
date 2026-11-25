#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
Si7021Full sensor(connection);                                          // Create Si7021 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // RH without hold master, temperature of the same conversion
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    Serial.print("humidity "); Serial.print(sensor.read_humidity(), 1); Serial.print(" %RH"); Serial.println();
    // sensor.read_humidity() → float %RH (NAN on failure)
    Serial.print("temperature "); Serial.print(sensor.read_temperature(), 2); Serial.print(" C"); Serial.println();
    // sensor.read_temperature() → float °C, new conversion (NAN on failure)
    Serial.print("temperature of last RH measurement "); Serial.print(sensor.read_temperature_previous(), 2); Serial.print(" C"); Serial.println();
    // sensor.read_temperature_previous() → float °C, command 0xE0, no new conversion (NAN on failure)
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
                                                                        // 16-bit code, status bits included
    Serial.print("raw RH "); Serial.print((unsigned)raw); Serial.println();
    sensor.read_raw_temperature(raw);                                   // Read raw temperature code, (code) → bool
    Serial.print("raw T "); Serial.print((unsigned)raw); Serial.println();

    sensor.set_resolution(11, 11);                                      // Set resolution, (rh_bits, temperature_bits) → bool
                                                                        // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    sensor.get_resolution(rh_bits, t_bits);                             // Get resolution, (rh_bits, t_bits) → void
    Serial.print("resolution "); Serial.print((unsigned)rh_bits); Serial.print(" / "); Serial.print((unsigned)t_bits); Serial.print(" bit"); Serial.println();
    Serial.print("conversion time "); Serial.print(sensor.conversion_time() * 1000.0f, 1); Serial.print(" ms"); Serial.println();
    // sensor.conversion_time() → float s, typical RH + temperature time
    sensor.set_resolution(12, 14);

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 3.1 mA; readings drift while on
    Serial.print("heater on "); Serial.print((unsigned)sensor.is_heater_on()); Serial.println();
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);
    Serial.print("VDD low "); Serial.print((unsigned)sensor.vdd_low()); Serial.println();
    // sensor.vdd_low() → bool, true when VDD is below about 1.9 V
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
                                                                        // 0x3A after reset
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void
                                                                        // reserved bits are preserved
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    Serial.print("serial number 0x"); for (int s = 56; s >= 0; s -= 8) { uint8_t b = (uint8_t)((sn >> s) & 0xFF); if (b < 0x10) Serial.print("0"); Serial.print((unsigned)b, HEX); } Serial.println();
    Serial.print("device type 0x"); Serial.print((unsigned)sensor.device_type(), HEX); Serial.println();
    // sensor.device_type() → uint8_t, 0x15 = Si7021
    Serial.print("firmware 0x"); Serial.print((unsigned)sensor.firmware_revision(), HEX); Serial.println();
    // sensor.firmware_revision() → uint8_t, 0xFF = firmware 1.0
    Serial.print("dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // 0xFE, waits 15 ms
    Serial.print("done"); Serial.println();
}

void loop() {
    delay(1000);
}
