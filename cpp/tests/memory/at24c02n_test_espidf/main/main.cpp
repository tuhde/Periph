#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AT24C02N.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))



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

    I2CConnectionESPIDF connection(dev);
    I2CConnectionESPIDF idConnection(id_dev);
    AT24C02NFull ee(connection);

    CHECK(ee.size() == 256, "size");
    CHECK(ee.page_size() == 8, "page_size");

    CHECK(ee.write_byte(0x0010, 0x5A), "write_byte ok");
    CHECK(ee.read_byte(0x0010) == 0x5A, "write_byte/read_byte round-trip");
    CHECK(ee.wait_ready(10), "wait_ready after write");

    uint8_t data[100], back[100];
    for (int i = 0; i < 100; i++) data[i] = (uint8_t)i;
    CHECK(ee.write(0x0070, data, 100), "page-split write ok");
    CHECK(ee.read(0x0070, back, 100), "read ok");
    bool same = true;
    for (int i = 0; i < 100; i++) same = same && back[i] == data[i];
    CHECK(same, "page-split write/read round-trip");

    uint8_t page[8], pb[8];
    for (int i = 0; i < 8; i++) page[i] = (uint8_t)i;
    CHECK(ee.write_page(0x00C0, page, 8), "write_page ok");
    ee.read(0x00C0, pb, 8);
    same = true;
    for (int i = 0; i < 8; i++) same = same && pb[i] == page[i];
    CHECK(same, "write_page round-trip");
    CHECK(!ee.write_page(0x00CC, page, 8), "write_page crossing rejected");

    CHECK(ee.fill(0x0040, 100, 0xC3), "fill ok");
    ee.read(0x0040, back, 100);
    same = true;
    for (int i = 0; i < 100; i++) same = same && back[i] == 0xC3;
    CHECK(same, "fill");
    ee.fill(0x0040, 100);
    ee.read(0x0040, back, 100);
    same = true;
    for (int i = 0; i < 100; i++) same = same && back[i] == 0;
    CHECK(same, "fill default zero");

    uint8_t tail[2] = {0x12, 0x34}, tail_back[2] = {0, 0};
    CHECK(ee.write(0x00FE, tail, 2), "last bytes write");
    ee.read(0x00FE, tail_back, 2);
    CHECK(tail_back[0] == 0x12 && tail_back[1] == 0x34, "last bytes round-trip");
    CHECK(!ee.read(0x00FF, tail_back, 2), "wrap rejected");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
