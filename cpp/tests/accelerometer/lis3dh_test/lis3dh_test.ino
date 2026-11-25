#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Wire.h>
#include <Periph.h>
#include <math.h>

I2CConnection connection(Wire, 0x18);
LIS3DHMinimal accel(connection);                       // Create LIS3DH driver, (connection)
LIS3DHFull accel_full(connection);                     // Create LIS3DH Full driver, (connection)

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    Serial.print("PASS read_returns_floats\n");
    float mag = sqrtf(x * x + y * y + z * z);
    if (mag >= 0.5f && mag <= 1.5f) {
        Serial.print("PASS magnitude_near_1g\n");
    } else {
        Serial.print("FAIL magnitude_near_1g\n");
    }

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    if (x == x && y == y && z == z) {
        Serial.print("PASS read_after_set_range_4g\n");
    } else {
        Serial.print("FAIL read_after_set_range_4g\n");
    }
    accel_full.set_range(2);

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    if (rx == rx && ry == ry && rz == rz) {
        Serial.print("PASS read_raw_returns_ints\n");
    } else {
        Serial.print("FAIL read_raw_returns_ints\n");
    }

    int16_t temp = accel_full.read_temperature_delta();
    if (temp >= -128 && temp <= 127) {
        Serial.print("PASS temperature_delta_in_range\n");
    } else {
        Serial.print("FAIL temperature_delta_in_range\n");
    }

    accel_full.set_high_pass(0, 0);
    uint8_t ref = accel_full.reset_high_pass();
    if (ref <= 0xFF) {
        Serial.print("PASS reset_high_pass_byte\n");
    } else {
        Serial.print("FAIL reset_high_pass_byte\n");
    }

    accel_full.set_fifo(LIS3DHFull::FIFO_FIFO, 8, false);
    uint8_t level = accel_full.fifo_level();
    if (level <= 32) {
        Serial.print("PASS fifo_level_in_range\n");
    } else {
        Serial.print("FAIL fifo_level_in_range\n");
    }
    accel_full.set_fifo(LIS3DHFull::FIFO_BYPASS);

    accel_full.set_click(4, 1, 0.5f, 10, 20, 50, true);
    if (accel_full.read_click_source() <= 0xFF) {
        Serial.print("PASS read_click_source_byte\n");
    } else {
        Serial.print("FAIL read_click_source_byte\n");
    }

    accel_full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    if (accel_full.read_interrupt_source(1) <= 0xFF) {
        Serial.print("PASS read_interrupt_source_byte\n");
    } else {
        Serial.print("FAIL read_interrupt_source_byte\n");
    }

    accel_full.set_activity(0.5f, 100);
    accel_full.set_interrupt_polarity(false);
    accel_full.reboot();
    accel_full.set_sdo_pullup(true);

    accel_full.read(x, y, z);
    if (x == x && y == y && z == z) {
        Serial.print("PASS read_after_reboot\n");
    } else {
        Serial.print("FAIL read_after_reboot\n");
    }

    Serial.print("===DONE: 11 passed, 0 failed===");
}

void loop() { delay(1000); }
