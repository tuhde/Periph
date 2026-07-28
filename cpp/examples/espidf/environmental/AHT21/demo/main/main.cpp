#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AHT21.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x38

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(I2C_SDA),
        .scl_io_num = static_cast<gpio_num_t>(I2C_SCL),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = AHT_ADDR,
        .scl_speed_hz    = 100000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AHT21Full aht(connection);                                          // Create AHT21 driver, (connection, addr=0x38) → void

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during construction.
    printf("Calibrated: %d\n", aht.is_calibrated());                   // Check calibration status, () → bool

    printf("Time     T (C)      RH (%%)     Dew (C)\n");
    for (int n = 0; n < 60; n++) {
        // --- Each reading requires an 80 ms measurement cycle ---
        // The sensor cannot output data faster than this; the driver
        // handles the trigger + wait internally.
        float t, h;
        bool crc_ok = aht.read_with_crc(t, h);                         // Read with CRC verification, (temperature_c, humidity_pct) → bool
        if (!crc_ok) {
            printf("CRC error at sample %d\n", n);
            vTaskDelay(pdMS_TO_TICKS(5000));
            continue;
        }

        // --- Magnus formula dew-point approximation ---
        // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
        // dew_point = (243.04 * gamma) / (17.625 - gamma)
        // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
        float gamma = logf(h / 100.0f) + (17.625f * t) / (243.04f + t);
        float dew   = (243.04f * gamma) / (17.625f - gamma);

        printf("%-8d %-10.2f %-10.2f %-10.2f\n", n, (double)t, (double)h, (double)dew);
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
