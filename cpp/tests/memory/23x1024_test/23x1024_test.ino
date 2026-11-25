#include <SPI.h>
#include <Periph.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN SS
#endif
#ifndef TEST_SCK
#define TEST_SCK SCK
#endif
#ifndef TEST_MOSI
#define TEST_MOSI MOSI
#endif
#ifndef TEST_MISO
#define TEST_MISO MISO
#endif

static void out(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#ifdef ARDUINO_ARCH_ESP32
    SPI.begin(TEST_SCK, TEST_MISO, TEST_MOSI, TEST_CS_PIN);
#else
    SPI.begin();
#endif

    SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);                        // Create SPI connection, (SPI, cs_pin, settings) → SPIConnection
    SRAM23X1024Full sram(connection);                                          // aborts if the MODE register round-trip fails
    check_true(true, "init_mode_roundtrip");
    check_true(sram.size() == 131072, "size");
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "default_mode_sequential");

    sram.write_byte(0x00000, 0xA5);
    check_true(sram.read_byte(0x00000) == 0xA5, "byte_roundtrip_first");
    sram.write_byte(0x1FFFF, 0x5A);
    check_true(sram.read_byte(0x1FFFF) == 0x5A, "byte_roundtrip_last");
    sram.write_byte(0x10000, 0x3C);
    check_true(sram.read_byte(0x10000) == 0x3C && sram.read_byte(0x00000) == 0xA5, "address_bit16_distinct");

    static uint8_t pattern[1000], back[1000];
    for (int i = 0; i < 1000; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    sram.write(0x00400, pattern, sizeof(pattern));
    sram.read(0x00400, back, sizeof(back));
    check_true(memcmp(pattern, back, sizeof(pattern)) == 0, "block_roundtrip_chunked");

    sram.fill(0x02000, 100, 0xC3);
    bool fill_ok = true;
    for (int i = 0; i < 100; i++) fill_ok = fill_ok && sram.read_byte(0x02000 + i) == 0xC3;
    check_true(fill_ok && sram.read_byte(0x02000 + 100) != 0xC3, "fill_range");

    sram.write(0x1FFF0, pattern, 16);
    sram.read(0x1FFF0, back, 16);
    check_true(memcmp(pattern, back, 16) == 0, "block_at_array_end");

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_PAGE, "set_mode_page");
    sram.write(0x04010, pattern, 64);          // crosses the pages at 0x4020 and 0x4040
    sram.read(0x04010, back, 64);
    check_true(memcmp(pattern, back, 64) == 0, "page_mode_crossing_linear");

    sram.set_mode(SRAM23X1024Full::MODE_BYTE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_BYTE, "set_mode_byte");
    sram.write(0x05000, pattern, 8);
    sram.read(0x05000, back, 8);
    check_true(memcmp(pattern, back, 8) == 0, "byte_mode_block");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "set_mode_sequential");

    sram.reset_io();
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "reset_io_stays_spi");
    check_true(sram.read_byte(0x00000) == 0xA5, "data_survives_reset_io");

    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed); Serial.println(" failed===");
}

void loop() {
}
