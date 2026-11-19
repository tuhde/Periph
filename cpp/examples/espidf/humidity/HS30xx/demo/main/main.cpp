#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HS30xx.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x44

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { vTaskDelay(pdMS_TO_TICKS(100)); }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = AHT_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HS30xxFull sensor(connection);                                  // Create HS30xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    // --- Optional: show and change the stored resolution (set show_resolution to true) ---
    // The NVM is only reachable within 10 ms after power-up, so this needs power_cycle().
    const bool show_resolution = false;
    if (show_resolution) {
    sensor.get_resolution(hb, tb, power_cycle);                     // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    printf("resolution %lu/%lu bit\n", (unsigned long)(hb), (unsigned long)(tb));
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    sensor.set_measurement_wait(15);                                // 12-bit RH + T need about 10 ms, (ms) → void
    }
    // --- Hygrometer: one reading every 2 s for 30 s ---
    // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
    for (uint8_t n = 0; n < 15; n++) {
        sensor.read_raw(rh_raw, t_raw, status);                     // Read raw values, (humidity_raw, temperature_raw, status) → bool
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%lu s: RH raw %lu T raw %lu status %lu | %.2f C %.1f %%RH dew %.2f C\n", (unsigned long)(n * 2), (unsigned long)(rh_raw), (unsigned long)(t_raw), (unsigned long)(status), t, rh, sensor.dew_point());
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
