#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LM25066.h"

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
    dev_cfg.device_address = 0x40;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LM25066Full hotswap(connection, 1.0f, false);      // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // Identify the chip, (buf, len)
    printf("%s %s %s\n", id, mdl, rev);
    hotswap.clear_faults();                                  // Release SMBA after power-up, ()
    // Hot-swap rail monitor: one line per second for a minute
    for (int i = 0; i < 60; i++) {
        if (i == 30) {
            hotswap.set_averaging(6);                        // 64-sample averaging, (exponent)
            hotswap.set_vin_uv_warn(10.0f);                  // VIN under-voltage warning at 10 V, (volts)
            hotswap.set_iin_warn(20.0f);                     // Input over-current warning at 20 A, (amps)
            hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN | LM25066Full::SOURCE_IIN_WARN); // Unmask both alerts, (source)
        }
        printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C\n",
               (double)hotswap.voltage_in(), (double)hotswap.voltage_out(), (double)hotswap.current(),
               (double)hotswap.current_avg(), (double)hotswap.power(), (double)hotswap.temperature());
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    printf("peak power %.2f W\n", (double)hotswap.power_peak()); // Peak input power, () → float W
    printf("diagnostic 0x%04X\n", (unsigned)hotswap.pollInterrupt()); // Alert flags, () → uint16_t
    hotswap.clear_faults();                                  // Finish servicing, ()
    // hotswap.operation_off();   // CUTS THE LOAD RAIL - only with explicit opt-in
    // hotswap.operation_on();
}
