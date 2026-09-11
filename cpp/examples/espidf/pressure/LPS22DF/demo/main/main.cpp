#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LPS22DF.h"

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

    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    LPS22DFFull chip(connection, false);                 // Create LPS22DF driver, (connection, spi=false)
    chip.configure(4, 0, true, 1, true);                   // Configure chip, (odr=25 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → None

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    vTaskDelay(pdMS_TO_TICKS(2000));
    float baseline_p = chip.pressure();                     // Read pressure, () → float Pa
    printf("Baseline: %.0f Pa\n", baseline_p);

    float pmin = baseline_p, pmax = baseline_p, psum = 0;
    float tmin = 0, tmax = 0, tsum = 0;
    float dmin = 0, dmax = 0, dsum = 0;
    for (int n = 0; n < 30; n++) {
        float p = chip.pressure();                          // Read pressure, () → float Pa
        float t = chip.temperature();                       // Read temperature, () → float °C
        float d = chip.altitude(baseline_p);                // Compute altitude, (sea_level_pa=baseline_p) → float m
        if (n == 0) { tmin = t; tmax = t; dmin = d; dmax = d; }
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        if (t < tmin) tmin = t;
        if (t > tmax) tmax = t;
        tsum += t;
        if (d < dmin) dmin = d;
        if (d > dmax) dmax = d;
        dsum += d;
        printf("%ds: %.0f Pa, T=%.2f C, dalt=%.3f m\n", n, p, t, d);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    printf("P min=%.0f max=%.0f mean=%.1f Pa\n", pmin, pmax, psum / 30);
    printf("T min=%.2f max=%.2f mean=%.2f C\n", tmin, tmax, tsum / 30);
    printf("dalt min=%.3f max=%.3f mean=%.3f m\n", dmin, dmax, dsum / 30);
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}