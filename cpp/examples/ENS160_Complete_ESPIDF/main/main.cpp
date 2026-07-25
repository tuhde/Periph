// Auto-generated ESP-IDF example for ENS160 (Complete).
// Mirrors the Arduino ENS160_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "ENS160.h"

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
        .device_address  = 0x52,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    ENS160Full chip(transport);  // Create ENS160 driver
    uint8_t aqi, validity;
    float tvoc, eco2;
    uint8_t maj, min, rel;
    float t_c, rh;
    float r_ohm;
    chip.status();                                    // Read validity flag, () → uint8_t
    // 0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output
    chip.read_air_quality(aqi, tvoc, eco2);           // Read air quality, (aqi, tvoc_ppb, eco2_ppm) → bool
    // polls until NEWDAT, returns AQI/TVOC/eCO2 atomically
    chip.read_tvoc();                                 // Read TVOC, () → float ppb
    chip.read_eco2();                                 // Read eCO2, () → float ppm
    chip.read_aqi();                                  // Read AQI, () → uint8_t
    chip.read_ethanol();                              // Read ethanol estimate, () → float ppb
    chip.set_compensation(22.0f, 50.0f);              // Set compensation, (temp_celsius, rh_percent) → void
    // external T/RH compensation improves gas algorithm accuracy
    chip.read_compensation_actuals(t_c, rh);          // Read compensation actuals, (temp_celsius, rh_percent) → void
    chip.read_raw_resistance(1);                      // Read raw sensor resistance, (sensor 1 or 4) → float Ω
    chip.get_firmware_version(maj, min, rel);         // Query firmware version, (major, minor, release) → void
    // requires IDLE mode; driver enters IDLE during call
    chip.configure_interrupt(true, false, true, true, false);  // Configure INTn, (enabled, active_high, push_pull, on_data, on_gpr) → void
    chip.sleep();                                     // Enter DEEP SLEEP, () → void
    chip.wake();                                      // Wake and resume STANDARD mode, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
