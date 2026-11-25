#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MB85RC.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char *label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
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

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x50;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    dev_cfg.device_address = 0x7C;
    i2c_master_dev_handle_t id_dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &id_dev);

    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);
    I2CConnectionESPIDF idConnection(id_dev);
    MB85RCFull fram(connection);

    check_true("size", fram.size() == 32768);

    fram.write_byte(0x0010, 0x5A);
    check_true("write_byte/read_byte round-trip", fram.read_byte(0x0010) == 0x5A);

    uint8_t data[100];
    uint8_t back[100];
    for (uint8_t i = 0; i < 100; i++) data[i] = i;
    fram.write(0x0100, data, 100);
    fram.read(0x0100, back, 100);
    check_true("chunked write/read round-trip", memcmp(data, back, 100) == 0);

    fram.fill(0x0100, 100, 0xC3);
    fram.read(0x0100, back, 100);
    bool filled = true;
    for (uint8_t i = 0; i < 100; i++) filled = filled && back[i] == 0xC3;
    check_true("fill", filled);
    fram.fill(0x0100, 100);
    fram.read(0x0100, back, 100);
    bool zeroed = true;
    for (uint8_t i = 0; i < 100; i++) zeroed = zeroed && back[i] == 0;
    check_true("fill default zero", zeroed);

    const uint8_t tail[2] = {0x12, 0x34};
    uint8_t tail_back[2] = {0, 0};
    fram.write(0x7FFE, tail, 2);
    fram.read(0x7FFE, tail_back, 2);
    check_true("last bytes round-trip", tail_back[0] == 0x12 && tail_back[1] == 0x34);

    check_true("wrap rejected", !fram.read(0x7FFF, tail_back, 2));

    uint16_t manufacturer = 0;
    uint8_t density = 0, product = 0;
    fram.read_device_id(idConnection, &manufacturer, &density, &product, 0x50);
    if (manufacturer == 0 && density == 0 && product == 0)
        printf("SKIP read_device_id (0x7C not answering)\n");
    else
        check_true("read_device_id", manufacturer == 0x00A && density == 0x5 && product == 0x10);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
