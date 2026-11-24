#include <math.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si1132.h"

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
    dev_cfg.device_address = Si1132Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1132Full sensor(connection);                                  // Create Si1132 driver, (connection)
    sensor.init();                                                  // Initialize the chip, () → bool

    // --- UV and light monitor ---
    // Prints the calibration bytes, reads UV index, visible and infrared counts
    // once per second, shows the effect of gain 0/2/4 and of the high-range bits,
    // reads the temperature and VDD auxiliary channels, runs autonomous mode at
    // 0.5 s while polling the interrupt status, then restricts the channels to UV
    // only and finishes with pause and reset.
    uint8_t cal[12];
    sensor.read_calibration(cal);                                   // Read the factory calibration, (out[12]) → bool
    printf("calibration %02X %02X %02X %02X\n", (unsigned)(cal[0]), (unsigned)(cal[1]), (unsigned)(cal[2]), (unsigned)(cal[3]));
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    for (int i = 0; i < 20; i++) {
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        printf("UV %.2f visible %lu infrared %lu\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    const uint8_t gains[3] = {0, 2, 4};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.set_gain(gains[i], gains[i]);                        // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
        sensor.read(uv, vis, ir);
        printf("gain %lu visible %lu infrared %lu\n", (unsigned long)(gains[i]), (unsigned long)(vis), (unsigned long)(ir));
    }
    sensor.set_gain(0, 0);
    sensor.set_high_range(true, true);                              // Set the high signal range, (visible, infrared) → bool
    sensor.read(uv, vis, ir);
    printf("high range visible %lu infrared %lu\n", (unsigned long)(vis), (unsigned long)(ir));
    sensor.set_high_range(false, false);
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
    printf("temperature raw %lu\n", (unsigned long)(aux));
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
    printf("vdd raw %lu\n", (unsigned long)(aux));
    sensor.start_autonomous(0.5f);                                  // Start autonomous mode, (interval_s 0-2.05) → bool
    sensor.enable_interrupt();                                      // Enable the INT pin, () → bool
    for (int i = 0; i < 10; i++) {
        vTaskDelay(pdMS_TO_TICKS(500));
        uint8_t st = sensor.poll_interrupt();                       // Poll interrupt status, () → uint8_t
        if (st) {
            sensor.read_raw(vis, ir, aux);                          // Read the data registers, (visible&, infrared&, aux&) → bool
            printf("INT 0x%02X visible %lu\n", (unsigned)(st), (unsigned long)(vis));
        }
    }
    sensor.disable_interrupt();                                     // Disable the INT pin, () → bool
    sensor.pause();                                                 // Pause autonomous mode, () → bool
    sensor.set_channels(true, false, false);                        // Select channels, (uv, visible, infrared, aux=false) → bool
    printf("UV only %.2f\n", (double)(sensor.read_uv_index()));     // Read the UV index, () → float
    sensor.reset();                                                 // Reset the chip, () → bool
}
