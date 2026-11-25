#include <math.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MAX44009.h"

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
    dev_cfg.device_address = MAX44009Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    // --- Configuration and first reading ---
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    printf("manual %d, lux %.2f, resolution %.3f lx/LSB\n", (int)cfg.manual, (double)lx, (double)res);

    // --- Arm a window of +-30 % around the current illuminance ---
    // The captureless callback only records the status; the loop below reacts to it.
    static volatile uint8_t pending = 0;
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    sensor.setThresholds(lx * 0.7f, lx * 1.3f);         // Set the interrupt window, (lowLux, highLux) → bool
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    sensor.onInterrupt([](uint8_t status) { pending = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Run for 60 s: react to interrupts, print the raw reading once a second ---
    for (int s = 0; s < 60; s++) {
        sensor.service();                               // Poll once and dispatch the callback, () → void
        if (pending) {
            pending = 0;
            float now = sensor.lux();                   // Read illuminance, () → float lux
            float level = now < 0.1f ? 0.1f : (now > 10000.0f ? 10000.0f : now);
            float backlight = 10.0f + 90.0f * (log10f(level) + 1.0f) / 5.0f;
            printf("lux %.2f -> backlight %.0f %%\n", (double)now, (double)backlight);
            sensor.setThresholds(now * 0.7f, now * 1.3f);  // Set the interrupt window, (lowLux, highLux) → bool
        }
        uint8_t e = 0, m = 0;
        sensor.readRaw(e, m);                           // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
        printf("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)sensor.isOverrange());
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
}
