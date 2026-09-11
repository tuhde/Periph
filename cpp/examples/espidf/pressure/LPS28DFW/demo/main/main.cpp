#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LPS28DFW.h"

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
        .device_address  = 0x5C,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    LPS28DFWFull chip(connection);               // Create LPS28DFW driver, (connection)
    chip.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                   LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=True, lpf_cfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    int samples = 0;
    for (int n = 0; n < 60; n++) {
        float p = 0.0f, t = 0.0f;
        chip.read(p, t);                         // Read both values, (pressure, temperature) → void
        float alt = 44330.0f * (1.0f - powf(p / 1013.25f, 1.0f / 5.255f));
        float elapsed = (n + 1) * 0.5f;
        printf("%.1fs  %.2f hPa  %.2f C  %.1f m\n", elapsed, p, t, alt);
        samples++;
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    printf("Total samples: %d\n", samples);

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}