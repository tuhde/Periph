#include <Wire.h>
#include <Periph.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5F
#endif

I2CConnection connection(Wire, TEST_ADDR);
HTS221Full sensor(connection);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}
#define CHECK(c, l) check_true((l), (c))
#define NEAR(v, lo, hi, l) check_true((l), ((v) >= (lo) && (v) <= (hi)))
#define TEST_DELAY_MS(ms) delay(ms)

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);


    CHECK(sensor.init(), "init");
    CHECK(sensor.who_am_i() == 0xBC, "who_am_i");
    HTS221Calibration cal = {};
    sensor.read_calibration(cal);
    CHECK(cal.h1_rh > cal.h0_rh && cal.t1_degc > cal.t0_degc, "calibration_plausible");
    float t = 0, rh = 0, hz = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -40.0f, 85.0f, "read_temperature_single");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    uint16_t ts = 0, hs = 0;
    CHECK(sensor.set_averaging(32, 64), "set_averaging");
    CHECK(sensor.get_averaging(ts, hs) && ts == 32 && hs == 64, "averaging_roundtrip");
    CHECK(sensor.set_averaging(16, 32), "set_averaging default");
    CHECK(sensor.set_output_data_rate(1), "set_output_data_rate");
    CHECK(sensor.get_output_data_rate(hz) && hz == 1.0f, "odr_roundtrip");
    TEST_DELAY_MS(1500);
    float ct = 0, crh = 0;
    CHECK(sensor.read_continuous(ct, crh), "read_continuous");
    NEAR(crh, 0.0f, 100.0f, "continuous_humidity_range");
    CHECK(sensor.set_output_data_rate(0), "set_output_data_rate one-shot");
    CHECK(sensor.set_heater(true) && sensor.is_heater_on(), "heater_on");
    CHECK(sensor.set_heater(false) && !sensor.is_heater_on(), "heater_off");

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
