// Auto-generated ESP-IDF example for LPS33HW (Demo).
// Mirrors the Arduino LPS33HW_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Lps33hw.h"

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
    LPS33HWFull chip(connection);  // Create LPS33HW driver, (connection)
    float p_Pa, t_C, altitude_m;

    // --- Initialization and configuration for altimeter preset ---
    // ODR=10 Hz gives ~10 Hz pressure output; BDU=1 latches the output
    // registers so a coherent 24-bit pressure can be read without tearing;
    // EN_LPFP=1 with LPFP_BW_ODR_20 (LPFP_CFG=1) gives an additional
    // ODR/20 low-pass filter that suppresses the kind of cabin-air
    // pressure bursts that would otherwise read as bogus altitude steps.
    chip.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr=10Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → void
    chip.reset_lpf();                                     // Flush transitory LPF state after enabling EN_LPFP, () → void

    // --- Main loop: poll P_DA rather than fixed delay ---
    // The chip updates pressure asynchronously at 10 Hz; spinning on the
    // STATUS register's P_DA bit lets us sample fresh data immediately
    // rather than racing the ODR clock with vTaskDelay().
    const float sea_level_Pa = 101325.0f;
    TickType_t last_print = xTaskGetTickCount();
    TickType_t last_autozero = last_print;

    while (1) {
        p_Pa = chip.pressure();                           // Read pressure, () → float Pa
        // waits for STATUS.P_DA before reading PRESS_XL..PRESS_H
        t_C = chip.temperature();                         // Read temperature, () → float °C
        TickType_t now = xTaskGetTickCount();

        if ((now - last_print) >= pdMS_TO_TICKS(1000)) {
            last_print = now;
            // --- Altitude via the barometric formula ---
            // The 44330 × (1 − (p/p0)^(1/5.255)) approximation is valid up
            // to ~11000 m and troposphere temperatures; for higher
            // altitudes use the full hypsometric equation.
            altitude_m = 44330.0f * (1.0f - powf(p_Pa / sea_level_Pa, 1.0f / 5.255f));  // Barometric altitude, () → float m
            printf("alt=%.2f m, T=%.2f C\n", altitude_m, t_C);
        }

        // --- AUTOZERO removes atmospheric drift every 10 s ---
        // Weather fronts shift sea-level pressure by ~1 hPa/hour, which
        // would otherwise show up as bogus altitude drift in a relative
        // (uncalibrated) altimeter; re-zeroing REF_P every 10 s cancels
        // that slow DC bias without throwing away the 10 Hz rate.
        if ((now - last_autozero) >= pdMS_TO_TICKS(10000)) {
            last_autozero = now;
            chip.set_autozero();                          // Set AUTOZERO, () → void
            // current pressure is stored in REF_P
            printf("Reference updated.\n");
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }
}