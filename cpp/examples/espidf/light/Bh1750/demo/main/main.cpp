#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Bh1750.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define BH1750_ADDR 0x23

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
    dev_cfg.device_address = BH1750_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void

    // --- Light meter ---
    // Reads the illuminance once in each one-time mode, prints continuous H-mode
    // values ten times per second for 5 s (cover and uncover the sensor), shows the
    // effect of the lowest and highest sensitivity on the raw count and the
    // measurement time, and finishes with reset and a power cycle.
    float lux = 0;
    uint16_t raw = 0;
    sensor.measure_once(BH1750Full::MODE_H, lux);                   // One-time measurement, (mode, lux&) → bool
    printf("H  %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_H2, lux);
    printf("H2 %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_L, lux);
    printf("L  %.2f lx\n", (double)(lux));
    sensor.start_continuous(BH1750Full::MODE_H);                    // Start continuous mode, (mode) → bool
    for (int i = 0; i < 50; i++) {
        vTaskDelay(pdMS_TO_TICKS(100));
        sensor.read_continuous(lux);                                // Read the latest result, (lux&) → bool
        printf("%.1f lx\n", (double)(lux));
    }
    sensor.set_sensitivity(0.45f);                                  // Set sensitivity, (factor) → bool
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printf("x0.45: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(3.68f);
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printf("x3.68: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(1.0f);
    sensor.reset();                                                 // Clear the data register, () → void
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
}
