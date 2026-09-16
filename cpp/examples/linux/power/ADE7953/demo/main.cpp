#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADE7953.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

int main() {
    I2CConnectionLinux conn(TEST_I2C_BUS, TEST_ADDR);
    ADE7953Full ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);                // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    // --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
    // The ADE7953 exposes power-quality events via the IRQ pin. Driving
    // OIA through the chip's own alert output lets the host react without
    // polling every reading every cycle.
    ade.configureOvercurrent(40.0f);                                  // Configure overcurrent, (threshold) → none

    // --- Sample at 1 Hz and emit one structured line per cycle ---
    // The energy accumulator resets on read by default (RSTREAD = 1), so
    // activeEnergy() returns watt-hours accumulated since the previous
    // call. Callers wanting a running total accumulate the returned deltas
    // themselves (or disable read-with-reset and track the 24-bit
    // register's own rollovers instead).
    std::printf("%-10s %-10s %-10s %-12s\n", "V", "A", "W", "Wh/s");
    while (true) {
        float v = ade.voltage();                                      // Read bus voltage, () → V
        float i = ade.current();                                      // Read load current, () → A
        float p = ade.activePower();                                  // Read active power, () → W
        float e = ade.activeEnergy();                                 // Read active energy, () → Wh
        std::printf("%-10.2f %-10.3f %-10.2f %-12.5f\n", v, i, p, e);
        usleep(1000000);
    }
    return 0;
}