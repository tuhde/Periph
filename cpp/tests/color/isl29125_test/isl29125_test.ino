#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, Isl29125Minimal::I2C_ADDRESS);

    Isl29125Minimal sensor(connection);                    // Create Isl29125 driver, (connection)
    Isl29125Full full(connection);                          // Create Isl29125 Full driver, (connection)

    Isl29125Counts c = {0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the three channels, (counts&) → bool
    check_true(c.red <= 0xFFFF && c.green <= 0xFFFF && c.blue <= 0xFFFF, "read_range");

    check_true(full.deviceId() == 0x7D, "device_id");       // Read DEVICE_ID, () → uint8_t
    check_true(full.setRange(375) && full.setRange(10000), "set_range");
    check_true(full.setResolution(12) && full.setResolution(16), "set_resolution");
    check_true(!full.setRange(100) && !full.setResolution(8), "invalid_config");
    check_true(full.setIrCompensation(true, 0x3F), "ir_compensation");
    full.setSync(false);
    full.readRgb(c);
    check_true(c.green <= 0xFFFF, "read_rgb_range");
    check_true(full.readGreen() <= 0xFFFF && full.readRed() <= 0xFFFF && full.readBlue() <= 0xFFFF, "read_single");
    check_true(full.setMode(Isl29125Full::MODE_GREEN), "mode_green");
    check_true(full.readGreen() <= 0xFFFF, "green_mode_reads");
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.setThresholds(100, 60000), "set_thresholds");
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);
    check_true(lo == 100 && hi == 60000, "thresholds_roundtrip");
    check_true(full.setPersistence(2) && full.setInterruptChannel(Isl29125Full::CHANNEL_GREEN), "interrupt_config");
    full.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.status();
    check_true((full.pollInterrupt() & Isl29125Full::STATUS_THRESHOLD) == 0, "status_clean");
    full.setMode(Isl29125Full::MODE_POWER_DOWN);
    full.softReset();
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.deviceId() == 0x7D, "soft_reset_id");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
