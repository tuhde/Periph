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
#define TEST_ADDR 0x6A
#endif

I2CConnection connection(Wire, TEST_ADDR);
LTC4151Full   ltc(connection);

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

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    { float v = ltc.voltage(); check_true("voltage range", v >= 7.0f && v <= 80.0f); }
    { float v = ltc.current(); check_true("current range", v >= 0.0f && v <= 4.096f); }
    { float v = ltc.power(); check_true("power range", v >= 0.0f && v <= 330.0f); }
    { float v = ltc.sense_voltage(); check_true("sense voltage range", v >= 0.0f && v <= 0.08192f); }
    { float v = ltc.adin_voltage(); check_true("adin range", v >= 0.0f && v <= 2.048f); }
    LTC4151Full::Readings r;
    ltc.read_all(r);
    { float v = r.vin; check_true("read_all vin", v >= 7.0f && v <= 80.0f); }
    check_true("read_raw vin", ltc.read_raw(LTC4151Full::VIN) > 0 && ltc.read_raw(LTC4151Full::VIN) < 4096);
    check_true("control default", ltc.read_control() == 0x0C);
    check_true("not snapshot mode", !ltc.is_snapshot_mode());
    float s = 0;
    check_true("snapshot vin", ltc.snapshot(LTC4151Full::VIN, s) && s >= 7.0f && s <= 80.0f);
    check_true("back in continuous", !ltc.is_snapshot_mode());
    ltc.set_stuck_bus_timer(false);
    check_true("stuck-bus timer off", ltc.read_control() == 0x08);
    ltc.set_stuck_bus_timer(true);
    check_true("stuck-bus timer on", ltc.read_control() == 0x0C);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
