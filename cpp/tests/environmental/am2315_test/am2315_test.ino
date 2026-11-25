#include <Wire.h>
#include <Periph.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 100000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

I2CConnection connection(Wire, TEST_ADDR);
AM2315Full sensor(connection);

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
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    CHECK(sensor.read_device_info(model, version, device_id), "read_device_info ok");
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    CHECK(sensor.read_raw(rh_raw, t_raw), "read_raw ok");
    NEAR((float)rh_raw, 0.0f, 1000.0f, "raw humidity range");
    NEAR((float)(t_raw & 0x7FFF), 0.0f, 1250.0f, "raw temperature range");
    CHECK(sensor.read_fresh(t, rh), "read_fresh ok");
    NEAR(t, -40.0f, 125.0f, "fresh temperature range");
    CHECK(sensor.read_registers(0x00, 4, regs), "read_registers ok");

    CHECK(sensor.write_user_registers(0x1234, 0xABCD), "write_user_registers ok");
    CHECK(sensor.read_user_registers(user1, user2), "read_user_registers ok");
    CHECK(user1 == 0x1234 && user2 == 0xABCD, "user registers round trip");
    sensor.write_user_registers(0, 0);
    sensor.wake();

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
