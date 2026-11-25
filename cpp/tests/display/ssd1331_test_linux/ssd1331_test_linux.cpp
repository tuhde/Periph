#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif
#ifndef TEST_DC
#define TEST_DC 25
#endif
#ifndef TEST_RES
#define TEST_RES 24
#endif
#ifndef TEST_BUSY
#define TEST_BUSY 23
#endif

#include <cstdio>
#include <cstdlib>
#include "DisplayConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "SSD1331.h"
#include "../ssd1331_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { std::printf("PASS %s\n", label); passed++; }
    else    { std::printf("FAIL %s\n", label); failed++; }
}

static uint8_t frameBuffer[96 * 64 * 2];

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";

    OutputPinLinux dc(chip_path, TEST_DC);
    OutputPinLinux res(chip_path, TEST_RES, true);

    DisplayConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, &dc, &res, nullptr);
    SSD1331Full<DisplayConnectionLinux> chip(connection, frameBuffer);
    ssd1331Checks(chip, report);

    std::printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
