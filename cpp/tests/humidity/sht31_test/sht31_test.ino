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
#define TEST_ADDR 0x44
#endif

I2CConnection connection(Wire, TEST_ADDR);
SHT31Full sensor(connection);

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

    float t = 0, rh = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);
        CHECK(sensor.read(t, rh), "repeatability read");
    }
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);

    uint16_t status = 0;
    CHECK(sensor.read_status(status), "status readable");
    sensor.clear_status();
    CHECK(sensor.read_status(status) && !(status & 0x0010), "status bit 4 cleared");

    CHECK(sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH), "start_periodic");
    TEST_DELAY_MS(1200);
    CHECK(sensor.fetch(t, rh), "periodic fetch");
    sensor.stop_periodic();

    sensor.set_heater(true);
    CHECK(sensor.read_status(status) && (status & 0x2000), "heater bit set");
    sensor.set_heater(false);
    CHECK(sensor.read_status(status) && !(status & 0x2000), "heater bit cleared");

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read after soft reset");

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
