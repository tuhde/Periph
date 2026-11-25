#include <stdio.h>
#include <string.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MB85RC.h"

int passed = 0;
int failed = 0;

static void check_true(const char *label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x50, nullptr, nullptr, 2);
    I2CConnectionPicoSDK idConnection(i2c0, 0x7C);
    MB85RCFull fram(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

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
    return failed == 0 ? 0 : 1;
}
