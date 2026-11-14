#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include <math.h>
#include "I2CConnectionESPIDF.h"
#include "AM2315.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x5C

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
    AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    sensor.read(t, rh);                                                      // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    printf("%.1f C, %.1f %%RH\n", t, rh);
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    printf("temperature %.1f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    sensor.read_raw(rh_raw, t_raw);                                          // Read raw words, (humidity_raw, temperature_raw) → bool
                                                                             // humidity x10; temperature x10 as sign + magnitude
    printf("raw RH %u raw T %u\n", (unsigned)(rh_raw), (unsigned)(t_raw));
    sensor.read_fresh(t, rh);                                                // Real-time value, (t, rh) → bool
                                                                             // two reads >= 2 s apart; returns the second
    printf("fresh %.1f C, %.1f %%RH\n", t, rh);
    sensor.read_registers(0x08, 7, regs);                                    // Read registers, (start, count, out) → bool
                                                                             // function 0x03, 1-10 registers, CRC verified
    printf("register 0x08 = 0x%02X\n", (unsigned)(regs[0]));
    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    printf("model %u version %u device id 0x%08lX\n", (unsigned)(model), (unsigned)(version), (unsigned long)(device_id));
    sensor.write_user_registers(0x1234, 0xABCD);                             // Write user registers, (user1, user2) → bool
                                                                             // registers 0x10-0x13
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    printf("user registers 0x%02X 0x%02X\n", (unsigned)(user1), (unsigned)(user2));
    sensor.write_registers(0x10, regs, 2);                                   // Write registers, (start, data, len) → bool
                                                                             // only the user registers 0x10-0x13 are allowed
    sensor.wake();                                                           // Wake the sensor, () → void
                                                                             // dummy write, the NACK is ignored
    printf("last error %u\n", (unsigned)(sensor.last_error()));
    // sensor.last_error() → uint8_t, 0x80-0x84 after a sensor error response, else 0
    printf("done\n");
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
