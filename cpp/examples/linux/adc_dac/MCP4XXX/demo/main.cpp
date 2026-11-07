#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4XXX.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x58;
    I2CConnectionLinux connection(bus, addr);

    MCP4XXXFull pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω, hvc=nullptr, hvc_settle_ms=1)
    pot.init();                                              // Bus check, () → void


    // --- Identify the family member ---
    // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
    printf("network(s)=%d steps=%d pot=%s eeprom=%s\n", (int)(pot.channels()), (int)(pot.steps()), (pot.is_potentiometer()) ? "true" : "false", (pot.is_nonvolatile()) ? "true" : "false"); // Part properties, () → uint8_t / uint16_t / bool
    printf("power-up wiper %d %.1f ohm\n", (int)(pot.get_wiper(0)), (double)(pot.get_resistance(0))); // Read wiper, (channel=0) → uint16_t

    // --- Set 2.2 kOhm and report the quantisation error ---
    // The wiper snaps to the nearest step of RAB / max code.
    pot.set_resistance(2200.0f, 0);
    float actual = pot.get_resistance(0);                    // Read W-B resistance, (channel=0) → float Ω
    printf("target=2200 ohm code=%d actual=%.1f ohm error=%.1f ohm\n", (int)(pot.get_wiper(0)), (double)(actual), (double)(actual - 2200.0f));

    // --- Step the wiper up and down ---
    // increment / decrement send one command byte per step in one transaction and saturate at full scale and zero scale.
    const int moves[] = {8, 8, -4, 10};
    for (int i = 0; i < 4; i++) {
        int m = moves[i];
        uint16_t code = m > 0 ? pot.increment(m, 0) : pot.decrement(-m, 0); // Step wiper, (steps=1, channel=0) → uint16_t
    printf("    step %d -> code=%d R_WB=%.1f ohm\n", (int)(m), (int)(code), (double)(pot.get_resistance(0)));
    }

    // --- Ratios ---
    // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
    const float ratios[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    for (int i = 0; i < 5; i++) {
        pot.set_ratio(ratios[i], 0);                         // Set wiper ratio, (ratio 0.0-1.0, channel=0) → void
    printf("    ratio=%.2f code=%d R_WB=%.1f R_WA=%.1f\n", (double)(ratios[i]), (int)(pot.get_wiper(0)), (double)(pot.get_resistance(0)), (double)(pot.get_resistance_wa(0))); // Read W-A resistance (-1 on rheostats), (channel=0) → float Ω
    }

    // --- Dual parts: drive both networks ---
    if (pot.channels() == 2) {
        pot.set_both(32, 96);                                // Set both wipers, (code0, code1) → bool
    printf("    both: %d %d -> %d %d\n", (int)(pot.get_wiper(0)), (int)(pot.get_wiper(1)), (int)(pot.increment(5, 1)), (int)(pot.decrement(2, 1)));
    }

    // --- Shutdown and terminal disconnect ---
    // Shutdown ties W to B and opens A without losing the wiper; set_terminals(w = false) floats W.
    pot.shutdown(0);                                         // Shut network down, (channel=0) → void
    printf("shutdown=%s code=%d\n", (pot.is_shutdown(0)) ? "true" : "false", (int)(pot.get_wiper(0))); // Read state, (channel=0) → bool
    usleep((1000) * 1000);
    pot.resume(0);                                           // Leave shutdown, (channel=0) → void
    pot.set_terminals(true, false, true, 0);                 // Disconnect the wiper, (a, w, b, channel=0) → void
    pot.set_terminals(true, true, true, 0);                  // Reconnect all terminals, (a, w, b, channel=0) → void

    // --- Non-volatile parts: EEPROM wiper and data ---
    // store_wiper makes the setting survive power cycles; every EEPROM write uses one of the 1 000 000 write cycles, so do not do this in a loop.
    if (pot.is_nonvolatile()) {
        MCP4XXXStatus status;
        pot.read_status(status);                             // Read STATUS, (&status) → bool
    printf("    busy=%s\n", (status.eeprom_busy) ? "true" : "false");
    printf("    stored %d\n", (int)(pot.store_wiper(0)));    // Store the wiper in EEPROM, (channel=0) → int
        pot.write_data(0, 0x155);                            // Write calibration marker, (index 0-9, value 0-0x1FF) → bool
    printf("    data0=%d nv wiper=%d\n", (int)(pot.read_data(0)), (int)(pot.get_nv_wiper(0))); // Read back, (index) → int
    }

    // --- Back to the power-up state ---
    pot.reset();                                             // Emulate power-on reset, () → void
    printf("after reset: %d\n", (int)(pot.get_wiper(0)));
    return 0;
}
