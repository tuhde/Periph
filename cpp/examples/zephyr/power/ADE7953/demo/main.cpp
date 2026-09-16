#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADE7953.h"

#ifndef ADE7953_I2C_NODE
#define ADE7953_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ADE7953_ADDR
#define ADE7953_ADDR 0x38
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADE7953_I2C_NODE);
    I2CConnectionZephyr connection(dev, ADE7953_ADDR);
    ADE7953Full ade(connection, 251.0f, 30.0f);                     // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

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
    printk("%-10s %-10s %-10s %-12s\n", "V", "A", "W", "Wh/s");
    while (true) {
        float v = ade.voltage();                                      // Read bus voltage, () → V
        float i = ade.current();                                      // Read load current, () → A
        float p = ade.activePower();                                  // Read active power, () → W
        float e = ade.activeEnergy();                                 // Read active energy, () → Wh
        printk("%-10.2f %-10.3f %-10.2f %-12.5f\n", v, i, p, e);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}