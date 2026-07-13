#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/environmental/BME680.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);
    BME680Full bme(transport);                           // Create BME680 driver, (transport)
    uint8_t cid = bme.chip_id();                       // Read chip ID, () → int
                                                        // returns 0x61 for BME680
    check_true(cid == 0x61, "chip_id");

    bme.configure(BME680Full::OSRS_X1, BME680Full::OSRS_X1, BME680Full::OSRS_X1, BME680Full::MODE_SLEEP, BME680Full::FILTER_0);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → None
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME680Full::OSRS_X4, BME680Full::OSRS_X2, BME680Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → None
                                                        // changes conversion time vs resolution trade-off
    bme.set_filter(BME680Full::FILTER_7);               // Set IIR filter, (coeff 0–7) → None
                                                        // applies to temperature and pressure only
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c, duration_ms) → None
                                                        // sets target temperature and on-time for gas measurement
    bme.set_heater_profile(1, 200, 100);                // Configure heater profile 1, (index 0–9, temp_c, duration_ms) → None
    bme.select_heater_profile(0);                       // Select active profile, (index 0–9) → None
    bme.set_gas_enabled(true);                            // Enable gas conversion, (enabled) → None
    bme.set_heater_off(false);                           // Control heater override, (off) → None
    bme.set_ambient_temperature(25.0f);                  // Override ambient for heater calc, (temp_c) → None
    uint8_t st = bme.status();                          // Read status register, () → int

    float t, p, h, g;
    bme.read_all(t, p, h, g);                           // Read all sensors in one cycle, (t, p, h, g) → void
                                                        // returns (T, P, RH, R_gas) from single TPHG trigger
    bool gv = bme.gas_valid();                          // Check gas validity, () → bool
    bool hs = bme.heater_stable();                      // Check heater stability, () → bool
    bme.reset();                                        // Soft reset chip, () → None
                                                        // re-reads calibration and re-applies configuration

    Serial.print("T=");
    Serial.print(t, 1);
    Serial.print(" C, P=");
    Serial.print(p, 1);
    Serial.print(" hPa, RH=");
    Serial.print(h, 1);
    Serial.print(" %, R_gas=");
    Serial.print(g, 0);
    Serial.println(" Ohm");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
