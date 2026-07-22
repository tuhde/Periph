#include <cstdio>
#include <cmath>
#include "SPITransportLinux.h"
#include "NEO6.h"

// Requires a NEO-6 module wired to SPI (mode 0, <=200 kHz) with a clear sky
// view. Achieving an actual fix needs an outdoor antenna and can take up to
// ~26 s (cold start); this test only requires that well-typed values come
// back. SPI reads use write_read() with an empty command so every response
// byte is captured (see NEO6Minimal::_tryReadByte).
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
    SPITransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 200000);
    NEO6Minimal gps(transport, NEO6BusType::Spi);

    check_true("fix() starts at 0", gps.fix() == 0);
    check_true("latitude() starts at NAN", std::isnan(gps.latitude()));

    for (int i = 0; i < 3000; i++) {
        gps.update();
    }

    check_true("fix() is a valid quality code", gps.fix() == 0 || gps.fix() == 1 || gps.fix() == 2);
    check_true("satellites() is a non-negative int", gps.satellites() >= 0);
    if (gps.fix() > 0) {
        check_true("latitude() in range once fixed", gps.latitude() >= -90.0f && gps.latitude() <= 90.0f);
        check_true("longitude() in range once fixed", gps.longitude() >= -180.0f && gps.longitude() <= 180.0f);
        check_true("altitude() is populated once fixed", !std::isnan(gps.altitude()));
    } else {
        printf("note: no fix acquired during the test window (needs sky view)\n");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
