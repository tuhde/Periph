// ESP-IDF demo for BME688: room air probe with forced and parallel mode.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BME688.h"

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
    dev_cfg.device_address = 0x76;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    BME688Full bme(connection);                           // Create BME688 driver, (connection)
    bme.configure(BME688Full::OSRS_X2, BME688Full::OSRS_X16, BME688Full::OSRS_X1, BME688Full::MODE_SLEEP, BME688Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    printf("--- forced mode ---\n");
    for (int n = 0; n < 10; n++) {
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        float gm = logf((h > 0.01f ? h : 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * gm / (17.62f - gm);       // Magnus dew point, °C
        printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C\n", n, (double)t, (double)h, (double)p, (double)g, (double)dew);
        vTaskDelay(pdMS_TO_TICKS(5000));
    }

    printf("--- parallel mode ---\n");
    const int16_t temps[3] = {320, 250, 180};
    const uint8_t repeats[3] = {1, 2, 1};
    bme.start_parallel(temps, repeats, 3, 100.0f);      // Start parallel mode, (temps, repeats, steps=3, shared_wait_ms=100) → void
    for (int n = 0; n < 60; n++) {
        BME688Field f[3];
        uint8_t cnt = bme.read_parallel(f, 3);          // Read the new data fields, (out, max) → uint8_t count
        for (uint8_t i = 0; i < cnt; i++) {
            printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%d, stable=%d)\n", f[i].step, f[i].sub_index, (double)f[i].temperature, (double)f[i].humidity, (double)f[i].gas_resistance, f[i].gas_valid, f[i].heater_stable);
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    bme.stop_parallel();                                // Back to sleep, () → void
}
