#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP384.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x76,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMP384Full bmp(connection, false);                      // Create BMP384 driver, (connection, spi=false)

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const float SEA_LEVEL_HPA = 1013.25f;
    const TickType_t PERIOD_MS = 500;
    const TickType_t DURATION_MS = 30000;
    TickType_t start = xTaskGetTickCount();
    TickType_t next = start;
    unsigned int rows = 0;
    while (xTaskGetTickCount() - start < pdMS_TO_TICKS(DURATION_MS)) {
        if (xTaskGetTickCount() >= next) {
            float t = bmp.temperature();                    // Read temperature, () → float °C
            float p = bmp.pressure();                       // Read pressure, () → float hPa
            float altitude = 44330.0f * (1.0f - powf(p / SEA_LEVEL_HPA, 1.0f / 5.255f));
            float elapsed_ms = (float)(xTaskGetTickCount() - start) * portTICK_PERIOD_MS;
            printf("%.1fs  %.2f hPa  %.1f C  %.1f m\n", elapsed_ms / 1000.0f, p, t, altitude);
            rows++;
            next += pdMS_TO_TICKS(PERIOD_MS);
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }

    printf("Sampled %u rows over 30 s\n", rows);
    printf("===DONE: 0 passed, 0 failed===\n");
}
