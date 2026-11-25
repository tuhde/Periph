#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si7021.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x40

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
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.software_reset();                                            // Software reset, () → void
    printf("device type 0x%02X\n", (unsigned)sensor.device_type());
    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printf("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printf("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.device_type() / sensor.firmware_revision() → uint8_t
    printf("VDD low %u\n", (unsigned)sensor.vdd_low());

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, sensor.dew_point());
        // sensor.dew_point() → float °C
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    // --- Value accessors and raw codes ---
    printf("RH %.1f T %.2f T of last RH %.2f\n", sensor.read_humidity(), sensor.read_temperature(), sensor.read_temperature_previous());
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
    printf("raw RH %u\n", (unsigned)raw);
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    static const uint8_t PAIRS[4][2] = {{12, 14}, {10, 13}, {11, 11}, {8, 12}};
    for (uint8_t i = 0; i < 4; i++) {
        sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]);                // Set resolution, (rh_bits, temperature_bits) → bool
        uint32_t t0 = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
        sensor.read(t, rh);
        uint32_t dt = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS) - t0;
        sensor.get_resolution(rh_bits, t_bits);
        printf("%u/%u bit: %.2f C %.1f %%RH, %lu ms (typ. %.1f ms)\n", (unsigned)rh_bits, (unsigned)t_bits, t, rh, (unsigned long)dt, sensor.conversion_time() * 1000.0f);
    }
    sensor.set_resolution(12, 14);

    // --- Heater test: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    vTaskDelay(pdMS_TO_TICKS(10000));
    printf("heater on %u\n", (unsigned)sensor.is_heater_on());
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
