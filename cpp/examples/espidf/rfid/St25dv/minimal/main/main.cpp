#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "St25dv.h"

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}
static void pause_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    // The ST25DV answers on two addresses: one device handle each.
    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.scl_speed_hz = 400000;
    dev_cfg.device_address = 0x53;
    i2c_master_dev_handle_t user_dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &user_dev);
    dev_cfg.device_address = 0x57;
    i2c_master_dev_handle_t system_dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &system_dev);
    I2CConnectionESPIDF user_conn(user_dev, nullptr, nullptr, 2);                  // Create user-area connection, (dev 0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionESPIDF system_conn(system_dev, nullptr, nullptr, 2);              // Create system-area connection, (dev 0x57, int=null, en=null, reg_bytes=2)
    ST25DVMinimal tag(user_conn, system_conn);                                    // Create ST25DV driver, (user, system, gpo_active_high=false)
    tag.init();                                                         // Read MEM_SIZE, BLK_SIZE, IC_REF, () → bool
    static const uint8_t text[] = {'H', 'e', 'l', 'l', 'o', ' ', 'N', 'F', 'C'};
    tag.write(0x0100, text, sizeof(text));                              // Write user memory, (address, data, length) → bool
    while (true) {
        uint8_t buf[9];
        tag.read(0x0100, buf, sizeof(buf));                                 // Read user memory, (address, buf, length) → bool
        print_hex("memory ", buf, sizeof(buf));
        pause_ms(1000);
    }
}
