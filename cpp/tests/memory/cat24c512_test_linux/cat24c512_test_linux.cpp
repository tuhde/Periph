#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CAT24C512.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x50
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((l), (c))
#define NEAR(v, lo, hi, l) check_true((l), ((v) >= (lo) && (v) <= (hi)))

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR, nullptr, nullptr, 2);
    CAT24C512Full ee(connection);

    CHECK(ee.size() == 65536, "size");
    CHECK(ee.page_size() == 128, "page_size");

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

    uint8_t page[128], pb[128];
    for (int i = 0; i < 128; i++) page[i] = (uint8_t)i;
    CHECK(ee.write_page(0x0300, page, 128), "write_page ok");
    ee.read(0x0300, pb, 128);
    same = true;
    for (int i = 0; i < 128; i++) same = same && pb[i] == page[i];
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
    CHECK(ee.write(0xFFFE, tail, 2), "last bytes write");
    ee.read(0xFFFE, tail_back, 2);
    CHECK(tail_back[0] == 0x12 && tail_back[1] == 0x34, "last bytes round-trip");
    CHECK(!ee.read(0xFFFF, tail_back, 2), "wrap rejected");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
