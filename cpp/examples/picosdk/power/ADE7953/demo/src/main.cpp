#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADE7953.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x38);
    ADE7953Full ade(connection, 251.0f, 30.0f);                      // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    stdio_init_all();
    sleep_ms(2000);

    // --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
    // The ADE7953 exposes power-quality events via the IRQ pin. Driving
    // OIA through the chip's own alert output lets the host react without
    // polling every reading every cycle.
    ade.configureOvercurrent(40.0f);                                 // Configure overcurrent, (threshold) → none

    // --- Sample at 1 Hz and emit one structured line per cycle ---
    // The energy accumulator resets on read by default (RSTREAD = 1), so
    // activeEnergy() returns watt-hours accumulated since the previous
    // call. Callers wanting a running total accumulate the returned deltas
    // themselves (or disable read-with-reset and track the 24-bit
    // register's own rollovers instead).
    printf("%-10s %-10s %-10s %-12s\n", "V", "A", "W", "Wh/s");
    while (true) {
        float v = ade.voltage();                                     // Read bus voltage, () → V
        float i = ade.current();                                     // Read load current, () → A
        float p = ade.activePower();                                 // Read active power, () → W
        float e = ade.activeEnergy();                                // Read active energy, () → Wh
        printf("%-10.2f %-10.3f %-10.2f %-12.5f\n", v, i, p, e);
        sleep_ms(1000);
    }
}