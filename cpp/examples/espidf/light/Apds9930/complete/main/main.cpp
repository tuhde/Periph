// ESP-IDF example for the APDS-9930 (Complete) — every Full-class method.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Apds9930.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x39,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);                                       // Create I2C connection, (dev) → I2CConnectionESPIDF
    APDS9930Full apds(connection);                                              // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                               // exposes ALS and proximity configuration methods

    vTaskDelay(pdMS_TO_TICKS(110));
    apds.configure_als(0xDB, 0, false);                                        // Configure ALS, (atime=0xDB, again=0, agl=false) → void
    apds.configure_proximity(8, 0, 0, false, 0xFF);                           // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → void
    apds.disable_wait();                                                       // Disable wait timer, () → void
    apds.set_als_thresholds(100, 60000, 1);                                    // Set ALS thresholds, (low=100, high=60000, persistence=1) → void
    apds.set_proximity_thresholds(10, 200, 1);                                 // Set proximity thresholds, (low=10, high=200, persistence=1) → void
    apds.set_proximity_offset(0);                                             // Set proximity offset, (offset=0) → void
    apds.sleep_after_interrupt(false);                                        // Configure SAI, (enable=false) → void

    for (int i = 0; i < 10; i++) {
        vTaskDelay(pdMS_TO_TICKS(110));
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
        uint16_t c0 = apds.ch0();                                               // Read Ch0 raw, () → uint16_t count
        uint16_t c1 = apds.ch1();                                               // Read Ch1 raw, () → uint16_t count
        bool avalid, pvalid, psat, aint, pint;
        apds.status(avalid, pvalid, psat, aint, pint);                          // Read STATUS decoded, (avalid, pvalid, psat, aint, pint) → void
        printf("lux=%.1f lx  prox=%u  ch0=%u  ch1=%u  AVALID=%d  PVALID=%d\n",
               lx, p, c0, c1, avalid, pvalid);
    }
    apds.clear_interrupt(0);                                                    // Clear interrupts, (channel=0) → void
}