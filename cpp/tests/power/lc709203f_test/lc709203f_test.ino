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
#define TEST_ADDR 0x0B
#endif

I2CConnection connection(Wire, TEST_ADDR);
LC709203FFull* gaugePtr = nullptr;  // constructed in setup(): the constructor talks to the chip

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
    gaugePtr = new LC709203FFull(connection);
    LC709203FFull& gauge = *gaugePtr;

    check_true("voltage range", gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f);
    check_true("crc valid", gauge.valid());
    check_true("percent range", gauge.percent() >= 0.0f && gauge.percent() <= 100.0f);
    check_true("temperature range", gauge.temperature() > 0.0f && gauge.temperature() < 60.0f);
    check_true("rsoc range", gauge.rsoc() <= 100);
    check_true("profile_id", gauge.profile_id() != 0);
    check_true("profile", gauge.get_profile() == 1);
    check_true("set_temperature", (gauge.set_temperature(30.0f), fabsf(gauge.temperature() - 30.0f) < 0.2f));
    check_true("apa", (gauge.set_apa(0x19), gauge.get_apa() == 0x19));
    check_true("apt", (gauge.set_apt(0x1E), gauge.get_apt() == 0x1E));
    check_true("alarm_rsoc", (gauge.set_alarm_rsoc(20), gauge.get_alarm_rsoc() == 20));
    check_true("alarm_voltage", (gauge.set_alarm_voltage(3.5f), fabsf(gauge.get_alarm_voltage() - 3.5f) < 0.002f));
    check_true("pollInterrupt", gauge.pollInterrupt() <= 3);
    check_true("init_rsoc", (gauge.init_rsoc(), gauge.rsoc() <= 100));
    gauge.sleep();
    delay(1);
    gauge.wake();
    check_true("wake: not sleeping", !gauge.is_sleeping());
    check_true("wake: voltage readable", gauge.voltage() > 0.0f);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
