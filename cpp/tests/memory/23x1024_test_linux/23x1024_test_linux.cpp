#include <string.h>
#include <math.h>
#include <time.h>
#include <stdint.h>
#include <cstdio>
#include "SPIConnectionLinux.h"
#include "23X1024.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);    // Create SPI connection, (bus, device, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    SRAM23X1024Full sram(connection);                                          // aborts if the MODE register round-trip fails
    check_true("init_mode_roundtrip", true);
    check_true("size", sram.size() == 131072);
    check_true("default_mode_sequential", sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL);

    sram.write_byte(0x00000, 0xA5);
    check_true("byte_roundtrip_first", sram.read_byte(0x00000) == 0xA5);
    sram.write_byte(0x1FFFF, 0x5A);
    check_true("byte_roundtrip_last", sram.read_byte(0x1FFFF) == 0x5A);
    sram.write_byte(0x10000, 0x3C);
    check_true("address_bit16_distinct", sram.read_byte(0x10000) == 0x3C && sram.read_byte(0x00000) == 0xA5);

    static uint8_t pattern[1000], back[1000];
    for (int i = 0; i < 1000; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    sram.write(0x00400, pattern, sizeof(pattern));
    sram.read(0x00400, back, sizeof(back));
    check_true("block_roundtrip_chunked", memcmp(pattern, back, sizeof(pattern)) == 0);

    sram.fill(0x02000, 100, 0xC3);
    bool fill_ok = true;
    for (int i = 0; i < 100; i++) fill_ok = fill_ok && sram.read_byte(0x02000 + i) == 0xC3;
    check_true("fill_range", fill_ok && sram.read_byte(0x02000 + 100) != 0xC3);

    sram.write(0x1FFF0, pattern, 16);
    sram.read(0x1FFF0, back, 16);
    check_true("block_at_array_end", memcmp(pattern, back, 16) == 0);

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);
    check_true("set_mode_page", sram.get_mode() == SRAM23X1024Full::MODE_PAGE);
    sram.write(0x04010, pattern, 64);          // crosses the pages at 0x4020 and 0x4040
    sram.read(0x04010, back, 64);
    check_true("page_mode_crossing_linear", memcmp(pattern, back, 64) == 0);

    sram.set_mode(SRAM23X1024Full::MODE_BYTE);
    check_true("set_mode_byte", sram.get_mode() == SRAM23X1024Full::MODE_BYTE);
    sram.write(0x05000, pattern, 8);
    sram.read(0x05000, back, 8);
    check_true("byte_mode_block", memcmp(pattern, back, 8) == 0);

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true("set_mode_sequential", sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL);

    sram.reset_io();
    check_true("reset_io_stays_spi", sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true("data_survives_reset_io", sram.read_byte(0x00000) == 0xA5);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
