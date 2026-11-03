#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <string.h>
#include "I2CConnectionZephyr.h"
#include "MB85RC.h"

#ifndef FRAM_I2C_NODE
#define FRAM_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef FRAM_ADDR
#define FRAM_ADDR 0x50
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char *label, bool cond) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(FRAM_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, FRAM_ADDR, nullptr, nullptr, 2);
    I2CConnectionZephyr idConnection(i2c_dev, 0x7C);
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
    fram.read_device_id(idConnection, &manufacturer, &density, &product, FRAM_ADDR);
    if (manufacturer == 0 && density == 0 && product == 0)
        printk("SKIP read_device_id (0x7C not answering)\n");
    else
        check_true("read_device_id", manufacturer == 0x00A && density == 0x5 && product == 0x10);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
