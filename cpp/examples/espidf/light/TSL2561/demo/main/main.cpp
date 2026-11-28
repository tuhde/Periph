#include <math.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TSL2561.h"

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
    dev_cfg.device_address = TSL2561Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    /* Light meter with alarm: auto-ranged lux every 2 s for 30 s, then a ±50 % channel-0
       window with persistence 2 reported while the sensor is covered. */
    TSL2561Full sensor(connection);                     // Create TSL2561 Full driver, (connection)
    sensor.init();                                      // Power up, 1× gain, 402 ms, () → bool
    printf("ID 0x%02X\n", sensor.readId());              // Read the ID register, () → int

    /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
    uint16_t ch0 = 0, ch1 = 0;
    for (int i = 0; i < 15; i++) {
        float lx = sensor.autoRange();                  // Auto-range and read lux, () → float lux
        sensor.readRaw(ch0, ch1);                       // Read both channels, (ch0&, ch1&) → bool
        printf("%9.1f lx  gain %2u  %3u ms  ch0 %5u  ch1 %5u\n", (double)lx, (unsigned)sensor.getGain(),
              (unsigned)sensor.getIntegrationTime(), (unsigned)ch0, (unsigned)ch1);  // Current settings, () → uint8_t / uint16_t
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    /* Alarm: a window of ±50 % around the current channel-0 count. */
    sensor.readRaw(ch0, ch1);
    uint32_t high = (uint32_t)ch0 * 3 / 2;
    if (high > 65535) high = 65535;
    sensor.setThresholds(ch0 / 2, (uint16_t)high);      // Set channel-0 window, (low, high) → bool
    sensor.configureInterrupt(1, 2);                    // Level interrupt, 2 cycles outside, (mode, persist) → bool
    printf("window %u .. %u - cover the sensor now\n", (unsigned)(ch0 / 2), (unsigned)high);
    for (int i = 0; i < 10; i++) {
        printf("outside window: %d\n", (int)sensor.pollInterrupt());  // Check ch0 against the window, () → bool
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    sensor.clearInterrupt();                            // Release the level interrupt, () → bool
    sensor.configureInterrupt(0, 0);
    sensor.powerDown();                                 // Power down, () → bool
    printf("powered: %d\n", (int)sensor.isPowered());    // Power state, () → bool
}
