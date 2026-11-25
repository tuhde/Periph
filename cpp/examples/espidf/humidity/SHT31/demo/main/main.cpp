#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "SHT31.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x44

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
    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.soft_reset();                                                // Soft reset, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X\n", (unsigned)status);
    // --- Single shots at all three repeatability levels ---
    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);                                // Select repeatability, (level) → bool
        uint32_t t0 = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        uint32_t dt = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS) - t0;
        printf("level %u: %.2f C %.1f %%RH (%lu ms)\n", (unsigned)level, t, rh, (unsigned long)dt);
    }
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
    printf("raw %u %u, T %.2f, RH %.1f\n", (unsigned)raw_t, (unsigned)raw_rh, sensor.read_temperature(), sensor.read_humidity());

    // --- Periodic mode at 2 mps: ten samples ---
    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
    for (uint8_t got = 0; got < 10;) {
        if (!sensor.fetch(t, rh)) {                                     // Fetch the latest periodic result, (t, rh) → bool
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }
        got++;
        printf("periodic %u: %.2f C %.1f %%RH\n", (unsigned)got, t, rh);
    }
    sensor.stop_periodic();                                             // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    float before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    vTaskDelay(pdMS_TO_TICKS(10000));
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("heater status bit: %u\n", (unsigned)((status >> 13) & 1));
    printf("temperature rise: %.2f K\n", sensor.read_temperature() - before);
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X, dew point %.2f C\n", (unsigned)status, sensor.dew_point());
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
