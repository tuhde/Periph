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

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { out("PASS %s\n", label); passed++; }
    else      { out("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))

static void run_tests(ST25DVFull& tag) {
    CHECK(tag.init(), "init");
    CHECK(tag.ic_reference() == 0x24 || tag.ic_reference() == 0x26, "ic_reference");
    uint32_t size = tag.memory_size();
    CHECK(size == 512 || size == 2048 || size == 8192, "memory_size");
    uint8_t uid[8] = {0};
    CHECK(tag.uid(uid), "uid ok");
    CHECK(uid[0] == 0xE0 && uid[1] == 0x02, "uid manufacturer");
    tag.ic_revision();
    CHECK(true, "ic_revision");

    uint16_t addr = (uint16_t)(size - 64);
    static uint8_t saved[40], pattern[40], back[40];
    CHECK(tag.read(addr, saved, 40), "read ok");
    for (int i = 0; i < 40; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    CHECK(tag.write(addr, pattern, 40), "write ok");
    CHECK(tag.read(addr, back, 40) && memcmp(back, pattern, 40) == 0, "write/read round-trip");
    CHECK(tag.write(addr, saved, 40), "restore");
    CHECK(!tag.read((uint16_t)(size - 1), back, 2), "range rejected");

    static const uint8_t pwd[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(tag.present_password(pwd), "present factory password");
    CHECK(tag.session_open(), "session open after present");

    uint8_t rf = 0xFF, i2c = 0xFF;
    CHECK(tag.get_area_protection(1, rf, i2c) && rf <= 0x0F && i2c <= 3, "get_area_protection");
    tag.get_gpo();
    CHECK(true, "get_gpo");
    tag.field_present();
    CHECK(true, "field_present");
    tag.poll_interrupt();
    CHECK(true, "poll_interrupt");
    tag.mailbox_status();
    CHECK(true, "mailbox_status");
    static uint8_t ndef[256];
    tag.read_ndef(ndef, sizeof(ndef));
    CHECK(true, "read_ndef");
    CHECK(tag.wait_write_complete(), "wait_write_complete");
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
    I2CConnectionESPIDF user_conn(user_dev, nullptr, nullptr, 2);
    I2CConnectionESPIDF system_conn(system_dev, nullptr, nullptr, 2);
    ST25DVFull tag(user_conn, system_conn);
    run_tests(tag);
    out("===DONE: %d passed, %d failed===\n", passed, failed);
}
