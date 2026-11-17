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
#define TEST_ADDR 0x40
#endif

I2CConnection connection(Wire, TEST_ADDR);
HDC1080Full sensor(connection);

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
    sensor.init();
    CHECK((sensor.read_config() & 0xFF00) == 0x1000, "config after init");

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    static const uint8_t TB[4] = {14, 11, 11, 14};
    static const uint8_t HB[4] = {14, 11, 8, 8};
    for (uint8_t i = 0; i < 4; i++) {
        CHECK(sensor.set_resolution(TB[i], HB[i]), "set_resolution");
        CHECK(sensor.read(t, rh), "read at resolution");
    }
    sensor.set_resolution(14, 14);

    sensor.set_heater(true);
    CHECK(sensor.is_heater_on(), "heater on");
    sensor.set_heater(false);
    CHECK(!sensor.is_heater_on(), "heater off");
    CHECK(!sensor.battery_low(), "battery ok");
    CHECK(sensor.serial_id() != 0, "serial id nonzero");
    CHECK(sensor.manufacturer_id() == 0x5449, "manufacturer id");
    CHECK(sensor.device_id() == 0x1050, "device id");
    NEAR(sensor.conversion_time(), 0.005f, 0.02f, "conversion time");
    sensor.software_reset();
    CHECK(sensor.read(t, rh), "read after software reset");

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
