#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <cmath>
#include "UARTTransportZephyr.h"
#include "NEO6.h"

// Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
// actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
// this test only requires that well-typed values come back, not a fix.
#ifndef NEO6_UART_NODE
#define NEO6_UART_NODE DT_NODELABEL(uart1)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printk("PASS %s\n", label); passed++; }
    else           { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(NEO6_UART_NODE);
    UARTTransportZephyr transport(dev);
    NEO6Minimal gps(transport);

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
        printk("note: no fix acquired during the test window (needs sky view)\n");
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
