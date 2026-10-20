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
#include "UC1611s.h"
#include "../uc1611s_spi_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { std::printf("PASS %s\n", label); passed++; }
    else    { std::printf("FAIL %s\n", label); failed++; }
}

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";

    OutputPinLinux dc(chip_path, TEST_DC);
    OutputPinLinux res(chip_path, TEST_RES, true);

    DisplayConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, &dc, &res, nullptr, DisplayWireMode::kSimplex, 1000000);
    UC1611sSpiFull<DisplayConnectionLinux> chip(connection);
    uc1611sSpiChecks(chip, report);

    std::printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
