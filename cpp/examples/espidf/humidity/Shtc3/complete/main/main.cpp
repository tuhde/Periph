#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Shtc3.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define SHTC3_ADDR 0x70

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
    dev_cfg.device_address = SHTC3_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void
    sensor.init();                                                  // Soft reset and ID check, () → bool
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // normal mode, both CRCs verified, one retry on a bad CRC
    printf("temperature %.2f C humidity %.1f %%RH\n", (double)(t), (double)(rh));
    printf("humidity %.1f %%RH\n", (double)(sensor.read_humidity())); // Read humidity only, () → float %RH
                                                                    // NAN on failure
    printf("temperature %.2f C\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printf("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.read_id(id);                                        // Read the ID word, (id&) → bool
                                                                    // (id & 0x083F) == 0x0807 for the SHTC3
    printf("id %02X\n", (unsigned)(id));
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
                                                                    // 0.7 ms measurement, slightly noisier
    ok = sensor.read(t, rh);
    printf("low power %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(false);
    sensor.set_clock_stretching(true);                              // Select clock stretching, (on) → void
                                                                    // needs a controller that tolerates SCL held low
    ok = sensor.read(t, rh);
    sensor.set_clock_stretching(false);
    sensor.set_sleep_between(false);                                // Sleep after every read?, (on) → void
                                                                    // off keeps the sensor idle (higher supply current)
    ok = sensor.read(t, rh);
    sensor.set_sleep_between(true);
    sensor.wake();                                                  // Wake-up command, () → void
    sensor.sleep();                                                 // Sleep command, () → void
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    sensor.soft_reset();                                            // Soft reset, () → void
                                                                    // 0x805D, leaves the sensor idle
    printf("ok %lu\n", (unsigned long)(ok));
}
