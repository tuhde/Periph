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
HTU31DFull sensor(connection);

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


    sensor.init();                                          // Soft reset, () → void
    float t = 0, rh = 0;
    CHECK(sensor.read(t, rh), "read ok");                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    NEAR(t, -40.0f, 125.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -40.0f, 125.0f, "read_temperature_single");
    uint16_t tr = 0, rr = 0;
    CHECK(sensor.read_raw(tr, rr), "read_raw");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    uint32_t serial = 0;
    CHECK(sensor.serial_number(serial) && serial <= 0xFFFFFF, "serial_number");
    CHECK((sensor.diagnostic() & HTU31DFull::DIAG_NVM_ERR) == 0, "diagnostic_no_nvm_error");
    uint8_t orh = 0, ot = 0;
    for (uint8_t osr = 0; osr < 4; osr++) {
        CHECK(sensor.set_resolution(osr, osr), "set_resolution");
        sensor.get_resolution(orh, ot);
        CHECK(orh == osr && ot == osr, "resolution_roundtrip");
        CHECK(sensor.read(t, rh) && rh >= 0.0f && rh <= 100.0f, "read_at_osr");
    }
    sensor.set_resolution(3, 3);
    CHECK(fabsf(sensor.conversion_time() - 0.02032f) < 1e-4f, "conversion_time_osr33");
    sensor.start_conversion();
    TEST_DELAY_MS(30);
    CHECK(sensor.read_result(t, rh) && rh >= 0.0f && rh <= 100.0f, "start_conversion_read_result");
    sensor.set_heater(true);
    CHECK(sensor.is_heater_on(), "heater_on");
    sensor.set_heater(false);
    CHECK(!sensor.is_heater_on(), "heater_off");
    sensor.software_reset();
    CHECK(sensor.read(t, rh) && rh >= 0.0f && rh <= 100.0f, "read_after_reset");

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
