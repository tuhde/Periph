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
#define TEST_ADDR 0x70
#endif

I2CConnection connection(Wire, TEST_ADDR);
SHTC3Full sensor(connection);

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

    CHECK(sensor.init(), "init ok");                        // Soft reset and ID check, () → bool
    float t = 0, rh = 0;
    uint16_t tr = 0, rr = 0, id = 0;
    CHECK(sensor.read(t, rh), "read ok");                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    NEAR(t, -45.0f, 130.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -45.0f, 130.0f, "read_temperature_single");
    CHECK(sensor.read_raw(tr, rr), "read_raw");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    CHECK(sensor.read_id(id) && (id & 0x083F) == 0x0807, "read_id_product_code");
    sensor.set_low_power(true);
    CHECK(sensor.read(t, rh), "low_power_read");
    sensor.set_low_power(false);
    sensor.set_sleep_between(false);
    CHECK(sensor.read(t, rh), "read_without_sleep");
    CHECK(sensor.read(t, rh), "read_without_sleep_again");
    sensor.sleep();
    sensor.wake();
    sensor.set_sleep_between(true);
    sensor.set_clock_stretching(true);
    CHECK(sensor.read(t, rh), "read_with_clock_stretching");
    sensor.set_clock_stretching(false);
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read_after_reset");

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
