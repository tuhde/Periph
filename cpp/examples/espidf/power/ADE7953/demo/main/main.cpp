#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADE7953.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADE7953Full ade(connection, 251.0f, 30.0f);                              // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    // --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
    // The ADE7953 exposes power-quality events via the IRQ pin. Driving
    // OIA through the chip's own alert output lets the host react without
    // polling every reading every cycle.
    ade.configureOvercurrent(40.0f);                                          // Configure overcurrent, (threshold) → none

    // --- Sample at 1 Hz and emit one structured line per cycle ---
    // The energy accumulator resets on read by default (RSTREAD = 1), so
    // activeEnergy() returns watt-hours accumulated since the previous
    // call. Callers wanting a running total accumulate the returned deltas
    // themselves (or disable read-with-reset and track the 24-bit
    // register's own rollovers instead).
    printf("%-10s %-10s %-10s %-12s\n", "V", "A", "W", "Wh/s");
    while (1) {
        float v = ade.voltage();                                              // Read bus voltage, () → V
        float i = ade.current();                                              // Read load current, () → A
        float p = ade.activePower();                                          // Read active power, () → W
        float e = ade.activeEnergy();                                         // Read active energy, () → Wh
        printf("%-10.2f %-10.3f %-10.2f %-12.5f\n", v, i, p, e);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}