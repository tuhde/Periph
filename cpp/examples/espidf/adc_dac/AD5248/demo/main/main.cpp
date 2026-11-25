// Auto-generated ESP-IDF example for AD5248 (Demo).
// Mirrors the Arduino AD5248_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5248.h"

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
    dev_cfg.device_address = 0x2C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5248Full pot(connection, 10000.0f, 160.0f);                // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    // --- Sweep channel 0 across its full range ---
    // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
    // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
    for (int code = 0; code <= 255; code += 16) {
        pot.set_wiper(0, code);                              // Set channel 0 wiper, (channel 0-1, code 0-255) → void
        float ohms = pot.get_resistance(0);                  // Read W-B resistance, (channel 0-1) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
    // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → void
    float actual = pot.get_resistance(1);                    // Read back W-B resistance, (channel 0-1) → float Ω
    printf("ch1 actual ohm=%.1f error ohm=%.1f\n", (double)(actual), (double)(actual - 2200.0f));
    // --- Shutdown and resume channel 1 ---
    // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
    pot.shutdown(1);                                         // Shut channel 1 down, (channel 0-1) → void
    bool down = pot.is_shutdown(1);                          // Query driver-tracked state, (channel 0-1) → bool
    printf("ch1 shutdown=%d\n", (int)(down));
    vTaskDelay(pdMS_TO_TICKS(1000));
    pot.resume(1);                                           // Resume channel 1, (channel 0-1) → void
    // --- Restore the power-up preset ---
    pot.reset();                                             // Reset both channels to midscale, () → void
}
