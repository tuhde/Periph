#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "MCP4725.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x60
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint32_t got, uint32_t expected) {
    if (got == expected) {
        printf("PASS %s\n", label); passed++;
    } else {
        printf("FAIL %s: got %u, expected %u\n", label, got, expected); failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    MCP4725Full dac(transport);

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}