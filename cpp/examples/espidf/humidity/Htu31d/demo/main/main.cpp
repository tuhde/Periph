#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Htu31d.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define HTU31D_ADDR 0x40

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
    dev_cfg.device_address = HTU31D_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void

    // --- Condensation-aware hygrometer ---
    // Prints the serial number and the diagnostic byte, reads temperature and
    // humidity with the dew point 15 times, compares the four oversampling
    // settings with their typical conversion time, shows the heater effect for
    // 10 s, demonstrates the non-blocking conversion pair and soft-resets.
    uint32_t serial = 0;
    sensor.serial_number(serial);                                   // Read the serial number, (serial&) → bool
    printf("serial 0x%02X diagnostic 0x%02X\n", (unsigned)(serial), (unsigned)(sensor.diagnostic()));
    float t = 0, rh = 0;
    for (int i = 0; i < 15; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("%.2f C %.1f %%RH dew %.2f C\n", (double)(t), (double)(rh), (double)(sensor.dew_point()));
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
    for (uint8_t osr = 0; osr < 4; osr++) {
        sensor.set_resolution(osr, osr);                            // Set oversampling, (osr_rh, osr_t) → bool
        sensor.read(t, rh);
        printf("osr %lu typical %.2f ms: %.2f C %.1f %%RH\n", (unsigned long)(osr), (double)(sensor.conversion_time() * 1000.0f), (double)(t), (double)(rh));
    }
    sensor.set_resolution(3, 3);
    sensor.read(t, rh);
    printf("before heater %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
    printf("heater on %lu diagnostic 0x%02X\n", (unsigned long)(sensor.is_heater_on()), (unsigned)(sensor.diagnostic()));
    vTaskDelay(pdMS_TO_TICKS(10000));
    sensor.set_heater(false);                                       // Heater off
    sensor.read(t, rh);
    printf("after heater %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.start_conversion();                                      // Start a conversion, () → void
    vTaskDelay(pdMS_TO_TICKS(30));
    sensor.read_result(t, rh);                                      // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    printf("result %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.software_reset();                                        // Soft reset, () → void
}
