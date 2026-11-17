#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HDC1080.h"

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
    HDC1080Full sensor(connection);                                     // Create HDC1080 driver, (connection) → void
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, dew);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    vTaskDelay(pdMS_TO_TICKS(10000));
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh0 - rh1, t1c - t0c);
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
    sensor.read(t, rh);
    uint32_t dt = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS) - ts;
    printf("11/8 bit: %.2f C %.1f %%RH in %u ms\n", t, rh, (unsigned)(dt));
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
