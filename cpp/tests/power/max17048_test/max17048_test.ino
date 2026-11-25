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
#define TEST_ADDR 0x36
#endif

I2CConnection connection(Wire, TEST_ADDR);
Max17048Full gauge(connection);

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

    check_true("voltage range", gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f);
    check_true("percent range", gauge.percent() >= 0.0f && gauge.percent() <= 100.0f);
    check_true("version", (gauge.version() & 0xFFF0) == 0x0010);
    check_true("raw_voltage matches voltage", fabsf((gauge.raw_voltage() >> 4) * 1.25e-3f - gauge.voltage()) < 0.05f);
    check_true("charge_rate readable", fabsf(gauge.charge_rate()) < 1000.0f);
    check_true("rcomp", (gauge.set_rcomp(0x97), gauge.get_rcomp() == 0x97));
    check_true("compensate 20 C", (gauge.compensate_temperature(20.0f), gauge.get_rcomp() == 0x97));
    check_true("alert_soc", (gauge.set_alert_soc(10), gauge.get_alert_soc() == 10));
    gauge.set_alert_soc(4);
    float lo = 0, hi = 0;
    gauge.set_voltage_alert(3.0f, 4.2f);
    gauge.get_voltage_alert(&lo, &hi);
    check_true("voltage_alert", fabsf(lo - 3.0f) < 0.001f && fabsf(hi - 4.2f) < 0.001f);
    gauge.set_voltage_alert(0.0f, 5.1f);
    check_true("reset_voltage", (gauge.set_reset_voltage(2.8f), fabsf(gauge.get_reset_voltage() - 2.8f) < 0.001f));
    gauge.set_reset_voltage(3.0f);
    float hib = 0, act = 0;
    gauge.set_hibernate_thresholds(26.624f, 0.06f);
    gauge.get_hibernate_thresholds(&hib, &act);
    check_true("hibernate_thresholds", fabsf(hib - 26.624f) < 0.001f && fabsf(act - 0.06f) < 0.001f);
    gauge.clear_status(0x3F00);
    check_true("status flags cleared", (gauge.read_status() & 0x3F00) == 0);
    check_true("pollInterrupt", gauge.pollInterrupt() == 0);
    check_true("config", (gauge.write_config(gauge.read_config()), (gauge.read_config() >> 8) == 0x97));
    gauge.enable_sleep(true);
    gauge.sleep();
    delay(1);
    gauge.wake();
    gauge.enable_sleep(false);
    check_true("wake: voltage readable", gauge.voltage() > 0.0f);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
