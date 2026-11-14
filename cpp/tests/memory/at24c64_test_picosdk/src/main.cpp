#include <stdio.h>
#include <string.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AT24C64.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))



int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x50, nullptr, nullptr, 2);
    I2CConnectionPicoSDK idConnection(i2c0, 0x7C);
    AT24C64Full ee(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    CHECK(ee.size() == 8192, "size");
    CHECK(ee.page_size() == 32, "page_size");

    CHECK(ee.write_byte(0x0010, 0x5A), "write_byte ok");
    CHECK(ee.read_byte(0x0010) == 0x5A, "write_byte/read_byte round-trip");
    CHECK(ee.wait_ready(10), "wait_ready after write");

    uint8_t data[200], back[200];
    for (int i = 0; i < 200; i++) data[i] = (uint8_t)i;
    CHECK(ee.write(0x0070, data, 200), "page-split write ok");
    CHECK(ee.read(0x0070, back, 200), "read ok");
    bool same = true;
    for (int i = 0; i < 200; i++) same = same && back[i] == data[i];
    CHECK(same, "page-split write/read round-trip");

    uint8_t page[32], pb[32];
    for (int i = 0; i < 32; i++) page[i] = (uint8_t)i;
    CHECK(ee.write_page(0x0300, page, 32), "write_page ok");
    ee.read(0x0300, pb, 32);
    same = true;
    for (int i = 0; i < 32; i++) same = same && pb[i] == page[i];
    CHECK(same, "write_page round-trip");
    CHECK(!ee.write_page(0x037C, page, 8), "write_page crossing rejected");

    CHECK(ee.fill(0x0100, 100, 0xC3), "fill ok");
    ee.read(0x0100, back, 100);
    same = true;
    for (int i = 0; i < 100; i++) same = same && back[i] == 0xC3;
    CHECK(same, "fill");
    ee.fill(0x0100, 100);
    ee.read(0x0100, back, 100);
    same = true;
    for (int i = 0; i < 100; i++) same = same && back[i] == 0;
    CHECK(same, "fill default zero");

    uint8_t tail[2] = {0x12, 0x34}, tail_back[2] = {0, 0};
    CHECK(ee.write(0x1FFE, tail, 2), "last bytes write");
    ee.read(0x1FFE, tail_back, 2);
    CHECK(tail_back[0] == 0x12 && tail_back[1] == 0x34, "last bytes round-trip");
    CHECK(!ee.read(0x1FFF, tail_back, 2), "wrap rejected");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
