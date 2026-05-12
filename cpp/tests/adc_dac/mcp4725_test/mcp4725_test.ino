#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"

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
#define TEST_ADDR 0x60
#endif

I2CTransport transport(Wire, TEST_ADDR);
MCP4725Full dac(transport);

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

    dac.set_voltage(0.5f);
    check_true("set_voltage(0.5) accepted", true);

    dac.set_raw(2048);
    check_true("set_raw(2048) accepted", true);

    dac.set_voltage_eeprom(0.5f);
    check_true("set_voltage_eeprom(0.5) accepted", true);

    dac.set_raw_eeprom(2048);
    check_true("set_raw_eeprom(2048) accepted", true);

    MCP4725Full::ReadResult state = dac.read();
    check_true("read returns code", state.code <= 4095);
    check_true("read returns eeprom_code", state.eeprom_code <= 4095);
    check_true("read returns voltage_fraction", state.voltage_fraction >= 0.0f && state.voltage_fraction <= 1.0f);

    dac.set_power_down(MCP4725Full::PD_NORMAL);
    check_true("set_power_down(NORMAL) accepted", true);

    dac.set_power_down(MCP4725Full::PD_1K_GND);
    check_true("set_power_down(1K) accepted", true);

    dac.set_power_down(MCP4725Full::PD_100K_GND);
    check_true("set_power_down(100K) accepted", true);

    dac.set_power_down(MCP4725Full::PD_500K_GND);
    check_true("set_power_down(500K) accepted", true);

    dac.wake_up();
    check_true("wake_up accepted", true);

    dac.reset();
    check_true("reset accepted", true);

    bool ready = dac.is_eeprom_ready();
    check_true("is_eeprom_ready returns bool", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}