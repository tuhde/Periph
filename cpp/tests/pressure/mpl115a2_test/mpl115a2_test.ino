#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x60
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
    MPL115A2Full baro(connection);

    float p = baro.read_pressure();
    check_true(p >= 50000.0f && p <= 115000.0f, "pressure_range");
    float t = baro.read_temperature();
    check_true(t >= -40.0f && t <= 105.0f, "temperature_range");
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);
    check_true(padc <= 1023 && tadc <= 1023, "raw_range");
    float c = baro.compensate(padc, tadc);
    check_true(c >= 50000.0f && c <= 115000.0f, "compensate_range");
    float pa, tc;
    baro.read(pa, tc);
    check_true(pa >= 50000.0f && pa <= 115000.0f && tc >= -40.0f && tc <= 105.0f, "read_both");
    float hpa = baro.pressure_hpa();
    check_true(hpa >= 500.0f && hpa <= 1150.0f, "pressure_hpa");
    float alt = baro.altitude();
    check_true(alt >= -1000.0f && alt <= 6000.0f, "altitude");
    baro.reload_coefficients();
    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);
    check_true(a0 != 0.0f, "coefficients");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
