// ESP-IDF example for BMP581 (Demo).
// Precision altimeter: 10 Hz NORMAL mode for 30 seconds, then compares
// IIR bypass vs IIR coefficient 3 noise floor.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP581.h"

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
        .device_address  = 0x46,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    BMP581Full bmp(connection, false);                   // Create BMP581 driver, (connection, spi=false)
    bmp.configure(0x17, BMP581Full::OSR_16X, BMP581Full::OSR_4X, true);  // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → None

    float pressures[300], temps[300], alts[300];
    for (int n = 0; n < 300; n++) {
        pressures[n] = bmp.pressure();                    // Read pressure, () → float Pa
        temps[n] = bmp.temperature();                     // Read temperature, () → float °C
        alts[n] = bmp.altitude();                         // Compute altitude, (sea_level_pa=101325.0) → float m
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    float amin = alts[0], amax = alts[0];
    for (int n = 1; n < 300; n++) {
        if (alts[n] < amin) amin = alts[n];
        if (alts[n] > amax) amax = alts[n];
    }
    printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m\n", amin, amax, amax - amin);

    // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → None

    float alts2[300];
    for (int n = 0; n < 300; n++) {
        bmp.pressure();                                   // Read pressure, () → float Pa
        alts2[n] = bmp.altitude();                        // Compute altitude, (sea_level_pa=101325.0) → float m
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    float amin2 = alts2[0], amax2 = alts2[0];
    for (int n = 1; n < 300; n++) {
        if (alts2[n] < amin2) amin2 = alts2[n];
        if (alts2[n] > amax2) amax2 = alts2[n];
    }
    printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m\n", amin2, amax2, amax2 - amin2);

    float pmin = pressures[0], pmax = pressures[0], psum = 0;
    for (int n = 0; n < 300; n++) {
        if (pressures[n] < pmin) pmin = pressures[n];
        if (pressures[n] > pmax) pmax = pressures[n];
        psum += pressures[n];
    }
    printf("Min P=%.1f, max P=%.1f, mean P=%.1f Pa\n", pmin, pmax, psum / 300.0f);
}