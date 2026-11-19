// Auto-generated ESP-IDF example for MPL115A2 (Demo).
// Mirrors the Arduino MPL115A2_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPL115A2.h"

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
    dev_cfg.device_address = 0x60;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)
    while (1) {
        float a0, b1, b2, c12;
        baro.coefficients(a0, b1, b2, c12);               // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
        printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);

        // --- Barometer log ---
        // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
        // altitude and the raw counts, then the min/max of the run.
        float pmin = 1e9f, pmax = 0.0f;
        for (int n = 0; n < 15; n++) {
            float pa = baro.read_pressure();              // Read pressure, () → float Pa
            float hpa = baro.pressure_hpa();              // Read pressure, () → float hPa
            float alt = baro.altitude();                  // Compute altitude, (sea_level_pa=101325.0) → float m
            uint16_t padc, tadc;
            baro.read_raw(padc, tadc);                    // One conversion, (padc&, tadc&) → void
            if (pa < pmin) pmin = pa;
            if (pa > pmax) pmax = pa;
            printf("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)\n", n * 2, pa, hpa, alt, (int)padc, (int)tadc);
            vTaskDelay(pdMS_TO_TICKS(2000));
        }
        printf("Summary: min=%.0f Pa, max=%.0f Pa\n", pmin, pmax);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
