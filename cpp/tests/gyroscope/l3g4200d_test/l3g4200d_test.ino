#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x68
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

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
    L3G4200DMinimal gyro(connection);

    float x, y, z;
    gyro.angular_rate(x, y, z);
    check_true(x >= -50.0f && x <= 50.0f, "angular_rate_x_range");
    check_true(y >= -50.0f && y <= 50.0f, "angular_rate_y_range");
    check_true(z >= -50.0f && z <= 50.0f, "angular_rate_z_range");

    L3G4200DFull gyro_full(connection);
    check_true(gyro_full.who_am_i() == 0xD3, "who_am_i");

    gyro_full.configure(1, 0, 500);  // 200 Hz, ±500 dps
    float x2, y2, z2;
    gyro_full.angular_rate(x2, y2, z2);
    check_true(x2 >= -500.0f && x2 <= 500.0f, "configure_then_read_x");

    check_true(gyro_full.status() <= 0xFF, "status_readable");
    check_true(gyro_full.temperature() >= -50 && gyro_full.temperature() <= 100, "temperature_range");

    gyro_full.set_full_scale(2000);
    float x3, y3, z3;
    gyro_full.angular_rate(x3, y3, z3);
    check_true(x3 >= -2000.0f && x3 <= 2000.0f, "set_full_scale_2000");

    uint8_t samples = gyro_full.fifo_samples();
    check_true(samples <= 31, "fifo_samples_in_range");

    gyro_full.enable_fifo(2, 10);
    uint8_t samples2 = gyro_full.fifo_samples();
    check_true(samples2 <= 31, "fifo_after_enable");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
