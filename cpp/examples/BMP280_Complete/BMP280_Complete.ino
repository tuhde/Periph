#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/pressure/BMP280.h"

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
    BMP280Full bmp(transport);                           // Create BMP280 driver, (transport, spi=false)
    uint8_t cid = bmp.chip_id();                       // Read chip ID, () → int
                                                        // returns 0x58 for BMP280
    check_true(cid == 0x58, "chip_id");

    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1, BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
                                                        // writes ctrl_meas and config registers
    bmp.set_oversampling(BMP280Full::OSRS_X4, BMP280Full::OSRS_X2);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5) → None
                                                        // changes conversion time vs resolution trade-off
    bmp.set_mode(BMP280Full::MODE_FORCED);              // Set power mode, (mode 0/1/3) → None
    bmp.set_filter(BMP280Full::FILTER_4);               // Set IIR filter, (coeff 0–4) → None
                                                        // suppresses short-term pressure disturbances
    bmp.set_standby(BMP280Full::T_SB_125_MS);           // Set standby time, (t_sb 0–7) → None
                                                        // only relevant in normal mode
    uint8_t st = bmp.status();                         // Read status register, () → int

    float t = bmp.temperature();                        // Read temperature, () → float °C
    float p = bmp.pressure();                          // Read pressure, () → float hPa
    float alt = bmp.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
    float slp = bmp.sea_level_pressure(alt);           // Compute sea-level pressure, (altitude_m) → float hPa
    bmp.reset();                                       // Soft reset chip, () → None

    Serial.print("T=");
    Serial.print(t, 1);
    Serial.print(" C, P=");
    Serial.print(p, 1);
    Serial.print(" hPa, alt=");
    Serial.print(alt, 1);
    Serial.print(" m, slp=");
    Serial.print(slp, 1);
    Serial.println(" hPa");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
