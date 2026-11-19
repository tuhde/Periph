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
    // --- Optional: show and change the stored resolution (set show_resolution to true) ---
    // The NVM is only reachable within 10 ms after power-up, so this needs power_cycle().
    const bool show_resolution = false;
    if (show_resolution) {
    sensor.get_resolution(hb, tb, power_cycle);                     // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    Serial.print("resolution "); Serial.print((unsigned long)(hb)); Serial.print("/"); Serial.print((unsigned long)(tb)); Serial.print(" bit"); Serial.println();
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    sensor.set_measurement_wait(15);                                // 12-bit RH + T need about 10 ms, (ms) → void
    }
    // --- Hygrometer: one reading every 2 s for 30 s ---
    // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
    for (uint8_t n = 0; n < 15; n++) {
        sensor.read_raw(rh_raw, t_raw, status);                     // Read raw values, (humidity_raw, temperature_raw, status) → bool
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        Serial.print((unsigned long)(n * 2)); Serial.print(" s: RH raw "); Serial.print((unsigned long)(rh_raw)); Serial.print(" T raw "); Serial.print((unsigned long)(t_raw)); Serial.print(" status "); Serial.print((unsigned long)(status)); Serial.print(" | "); Serial.print(t, 2); Serial.print(" C "); Serial.print(rh, 1); Serial.print(" %RH dew "); Serial.print(sensor.dew_point(), 2); Serial.print(" C"); Serial.println();
        delay(2000);
    }
}

void loop() {
    delay(1000);
}
