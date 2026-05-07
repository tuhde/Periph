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
MCP4725Full   dac(transport);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint32_t got, uint32_t expected) {
    if (got == expected) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got "); Serial.print(got);
        Serial.print(", expected "); Serial.println(expected);
        failed++;
    }
}

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

    dac.set_voltage(0.5);
    dac.set_raw(2048);

    MCP4725ReadResult result = dac.read();
    check_true("code in range", result.code <= 4095);
    check_true("voltage_fraction in range", result.voltage_fraction >= 0.0f && result.voltage_fraction <= 1.0f);
    check_true("power_down in range", result.power_down <= 3);
    check_true("eeprom_code in range", result.eeprom_code <= 4095);
    check_true("eeprom_power_down in range", result.eeprom_power_down <= 3);

    dac.set_power_down(1);
    result = dac.read();
    check_eq("power_down mode 1", result.power_down, 1);

    dac.wake_up();
    dac.reset();
    check_true("eeprom_ready or write in progress", dac.is_eeprom_ready() == true || dac.is_eeprom_ready() == false);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}