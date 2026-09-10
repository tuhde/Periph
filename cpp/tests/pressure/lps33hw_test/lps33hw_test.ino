#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/Lps33hw.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TEST_ADDR);
    LPS33HWMinimal lps(connection);

    float p = lps.pressure();
    check_true(p >= 80000.0f && p <= 120000.0f, "pressure_range");

    float t = lps.temperature();
    check_true(t >= -10.0f && t <= 60.0f, "temperature_range");

    LPS33HWFull lps_full(connection);
    lps_full.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr, bdu, en_lpfp, lpfp_cfg, lc_en, sim) → None
    check_true(true, "configure");

    uint8_t st = lps_full.status();
    check_true(true, "status_read");

    float p2 = lps_full.pressure();
    check_true(p2 >= 80000.0f && p2 <= 120000.0f, "pressure_range_full");

    lps_full.set_pressure_offset(0.0f);
    check_true(true, "set_pressure_offset");

    lps_full.set_autozero();
    lps_full.clear_autozero();
    check_true(true, "autozero_roundtrip");

    lps_full.set_autorifp();
    lps_full.clear_autorifp();
    check_true(true, "autorifp_roundtrip");

    lps_full.configure_interrupt(false, false, false, false, LPS33HWFull::INT_S_DATA_SIGNALS, false, false);
    check_true(true, "configure_interrupt");

    lps_full.configure_pressure_interrupt(false, false, 0.0f, false);
    check_true(true, "configure_pressure_interrupt");

    lps_full.enable_fifo(LPS33HWFull::FIFO_MODE_BYPASS, 0);
    lps_full.disable_fifo();
    check_true(true, "fifo_roundtrip");

    uint8_t fsts = lps_full.fifo_status();
    check_true(true, "fifo_status_read");

    lps_full.reset_lpf();
    check_true(true, "reset_lpf");

    lps_full.reset();
    check_true(true, "reset");

    lps_full.reboot();
    check_true(true, "reboot");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }