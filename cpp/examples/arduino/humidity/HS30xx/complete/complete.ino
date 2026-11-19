#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x44);
HS30xxFull sensor(connection);                                      // Create HS30xx driver, (connection) → void

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { delay(100); }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    uint32_t id = 0;
    uint16_t reg = 0;
    bool ok = false;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    Serial.print(t, 2); Serial.print(" C, "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // NAN on failure
    Serial.print("temperature "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // 2-byte fetch, NAN on failure
    Serial.print("humidity "); Serial.print(rh, 1); Serial.print(" %RH"); Serial.println();
    sensor.read_raw(rh_raw, t_raw, status);                         // Read raw values, (humidity_raw, temperature_raw, status) → bool
                                                                    // 14-bit values; status 0 = valid, 1 = stale
    Serial.print("raw RH "); Serial.print((unsigned long)(rh_raw)); Serial.print(" raw T "); Serial.print((unsigned long)(t_raw)); Serial.print(" status "); Serial.print((unsigned long)(status)); Serial.println();
    sensor.set_measurement_wait(60);                                // Override the post-request wait, (ms) → void
                                                                    // default 40 ms; lower resolutions need less
    sensor.set_measurement_wait(40);                                // Back to the default wait
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    Serial.print("dew point "); Serial.print(t, 2); Serial.print(" C"); Serial.println();
    // NVM access only works within 10 ms after power-up, so it needs the power_cycle helper above.
    ok = sensor.get_resolution(hb, tb, power_cycle);                // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // bits are 8, 10, 12 or 14
    Serial.print("resolution "); Serial.print((unsigned long)(hb)); Serial.print("/"); Serial.print((unsigned long)(tb)); Serial.print(" bit (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    ok = sensor.read_id(id, power_cycle);                           // Read the 32-bit sensor ID, (id, power_cycle) → bool
    Serial.print("id "); Serial.print((unsigned long)(id)); Serial.print(" (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    sensor.enter_programming_mode();                                // Enter programming mode, () → void
                                                                    // only within 10 ms after power-up
    ok = sensor.read_nvm(0x06, reg);                                // Read an NVM register, (reg, value) → bool
                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    Serial.print("NVM 0x06 = "); Serial.print((unsigned long)(reg)); Serial.print(" (ok="); Serial.print((unsigned long)(ok)); Serial.print(")"); Serial.println();
    sensor.exit_programming_mode();                                 // Leave programming mode, () → void
}

void loop() {
    delay(1000);
}
