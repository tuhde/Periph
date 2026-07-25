#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/gas/ENS160.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x52);
    ENS160Full sensor(transport);                        // Create ENS160 driver, (transport)

    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);  // Get firmware version, (major&, minor&, release&) → void
                                                          // switches to IDLE, issues GET_APPVER, returns to STANDARD
    Serial.print("Firmware: ");
    Serial.print(major);
    Serial.print(".");
    Serial.print(minor);
    Serial.print(".");
    Serial.println(release);

    sensor.set_compensation(25.0f, 50.0f);               // Set compensation, (temp_celsius, rh_percent) → void
                                                          // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false);  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → void
                                                          // sets INTn pin behavior for new data notification

    Serial.println("Waiting for warm-up...");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            delay(1000);
        }
    }

    float tvoc = sensor.read_tvoc();                     // Read TVOC, () → float ppb
    float eco2 = sensor.read_eco2();                     // Read eCO2, () → float ppm
    uint8_t aqi = sensor.read_aqi();                     // Read AQI, () → uint8_t 1–5
    float ethanol = sensor.read_ethanol();               // Read ethanol, () → float ppb
                                                          // alias of DATA_TVOC at 0x22
    float r1 = sensor.read_raw_resistance(1);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float r4 = sensor.read_raw_resistance(4);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);  // Read compensation actuals, (temp_celsius&, rh_percent&) → void
                                                          // returns T/RH values used by sensor

    Serial.print("TVOC=");
    Serial.print(tvoc, 0);
    Serial.print(" ppb, eCO2=");
    Serial.print(eco2, 0);
    Serial.print(" ppm, AQI=");
    Serial.println(aqi);
    Serial.print("Ethanol=");
    Serial.print(ethanol, 0);
    Serial.print(" ppb, R1=");
    Serial.print(r1, 0);
    Serial.print(" Ohm, R4=");
    Serial.print(r4, 0);
    Serial.println(" Ohm");
    Serial.print("Actual T=");
    Serial.print(temp_actual, 1);
    Serial.print(" C, RH=");
    Serial.print(rh_actual, 1);
    Serial.println(" %");

    sensor.sleep();                                      // Enter deep sleep, () → void
                                                          // reduces current to ~10 uA
    delay(1000);
    sensor.wake();                                       // Wake and resume sensing, () → void
                                                          // transitions IDLE then STANDARD

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
