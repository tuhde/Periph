#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x46
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/BMP581.h"

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
    BMP581Full bmp(connection);

    check_true(bmp.chip_id() == 0x50, "chip_id");
    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);
    check_true(bmp._odr == 0x1C && bmp._osr_p == 0 && bmp._osr_t == 0, "configure_state");

    bmp.set_mode(BMP581Full.MODE_NORMAL);
    check_true(bmp._pwr_mode == BMP581Full.MODE_NORMAL, "set_mode");

    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);
    bmp.enable_drdy_interrupt(true);
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);

    float alt = bmp.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }