#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4XXX.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_PART
#define TEST_PART MCP4XXXPart::MCP4532
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x58
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    MCP4XXXFull pot(connection, TEST_PART, 10000.0f, 75.0f);

    pot.init();
    int max = (int)pot.steps() - 1;

    // --- Wiper write / readback ---
    pot.set_wiper(100 % (max + 1), 0);
    check_true("set/get wiper", pot.get_wiper(0) == 100 % (max + 1));
    pot.set_wiper(9999, 0);
    check_true("clamps high", pot.get_wiper(0) == max);
    pot.set_wiper(-5, 0);
    check_true("clamps low", pot.get_wiper(0) == 0);
    if (pot.channels() == 2) {
        pot.set_both(10, 20);
        check_true("set_both ch0", pot.get_wiper(0) == 10);
        check_true("set_both ch1", pot.get_wiper(1) == 20);
    }

    // --- Resistance and ratio ---
    pot.set_resistance(5075.0f, 0);
    check_true("set_resistance ~mid", abs((int)pot.get_wiper(0) - max / 2) <= 1);
    check_true("get_resistance ~5075", pot.get_resistance(0) > 4975.0f && pot.get_resistance(0) < 5175.0f);
    pot.set_ratio(0.5f, 0);
    check_true("get_ratio ~0.5", pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f);
    if (pot.is_potentiometer()) {
        pot.set_wiper(0, 0);
        check_true("R_WA at zero scale ~RAB", pot.get_resistance_wa(0) > 9975.0f && pot.get_resistance_wa(0) < 10175.0f);
    }

    // --- Increment / decrement (continuous) ---
    pot.set_wiper(max - 3, 0);
    check_true("increment saturates", pot.increment(10, 0) == max);
    pot.set_wiper(3, 0);
    check_true("decrement saturates", pot.decrement(10, 0) == 0);
    check_true("increment 1", pot.increment(1, 0) == 1);

    // --- Shutdown / terminals ---
    pot.set_wiper(max / 4, 0);
    pot.shutdown(0);
    check_true("is_shutdown", pot.is_shutdown(0));
    pot.resume(0);
    check_true("not shutdown", !pot.is_shutdown(0));
    check_true("wiper kept across shutdown", pot.get_wiper(0) == max / 4);
    pot.set_terminals(true, false, true, 0);
    bool ta, tw, tb;
    pot.get_terminals(0, ta, tw, tb);
    check_true("terminals", ta && !tw && tb);
    pot.set_terminals(true, true, true, 0);
    check_true("tcon restored", (pot.get_tcon() & 0xFF) == 0xFF);

    // --- Non-volatile parts ---
    if (pot.is_nonvolatile()) {
        MCP4XXXStatus status;
        pot.read_status(status);
        check_true("status no busy", !status.eeprom_busy);
        pot.set_wiper(max / 2, 0);
        check_true("store_wiper", pot.store_wiper(0) == max / 2);
        check_true("get_nv_wiper", pot.get_nv_wiper(0) == max / 2);
        pot.write_data(0, 0x155);
        check_true("data roundtrip", pot.read_data(0) == 0x155);
    }

    // --- Reset ---
    pot.set_wiper(1, 0);
    pot.reset();
    check_true("reset wiper", pot.get_wiper(0) == max / 2);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
