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

    sensor.software_reset();                                            // Software reset, () → void
    Serial.print("device type 0x"); Serial.print((unsigned)sensor.device_type(), HEX); Serial.println();
    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    Serial.print("serial number 0x"); for (int s = 56; s >= 0; s -= 8) { uint8_t b = (uint8_t)((sn >> s) & 0xFF); if (b < 0x10) Serial.print("0"); Serial.print((unsigned)b, HEX); } Serial.println();
    Serial.print("firmware 0x"); Serial.print((unsigned)sensor.firmware_revision(), HEX); Serial.println();
    // sensor.device_type() / sensor.firmware_revision() → uint8_t
    Serial.print("VDD low "); Serial.print((unsigned)sensor.vdd_low()); Serial.println();

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH dew point "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
        // sensor.dew_point() → float °C
        delay(2000);
    }

    // --- Value accessors and raw codes ---
    Serial.print("RH "); Serial.print(sensor.read_humidity(), 1); Serial.print(" T "); Serial.print(sensor.read_temperature(), 2); Serial.print(" T of last RH "); Serial.print(sensor.read_temperature_previous(), 2); Serial.println();
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
    Serial.print("raw RH "); Serial.print((unsigned)raw); Serial.println();
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    static const uint8_t PAIRS[4][2] = {{12, 14}, {10, 13}, {11, 11}, {8, 12}};
    for (uint8_t i = 0; i < 4; i++) {
        sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]);                // Set resolution, (rh_bits, temperature_bits) → bool
        uint32_t t0 = millis();
        sensor.read(t, rh);
        uint32_t dt = millis() - t0;
        sensor.get_resolution(rh_bits, t_bits);
        Serial.print((unsigned)rh_bits); Serial.print("/"); Serial.print((unsigned)t_bits); Serial.print(" bit: "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH, "); Serial.print(" ms (typ. "); Serial.print(sensor.conversion_time() * 1000.0f, 1); Serial.print(" ms)"); Serial.println();
    }
    sensor.set_resolution(12, 14);

    // --- Heater test: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    delay(10000);
    Serial.print("heater on "); Serial.print((unsigned)sensor.is_heater_on()); Serial.println();
    Serial.print("RH drop "); Serial.print(rh_before - sensor.read_humidity(), 1); Serial.print(" %RH, temperature rise "); Serial.print(sensor.read_temperature() - t_before, 2); Serial.print(" K"); Serial.println();
    sensor.set_heater(false);
}

void loop() {
    delay(1000);
}
