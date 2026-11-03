#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "display_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 4000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);
    OutputPinPicoSDK dc(16);
    OutputPinPicoSDK res(15);
    InputPinPicoSDK  busy(14);

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, &busy);
    displaySmoke(connection, false, report);

    // The PL022 SPI block has no hardware 3-wire mode: that wire mode must be rejected.
    DisplayConnectionPicoSDK halfDuplexHw(spi0, &cs, &dc, &res, &busy, DisplayWireMode::kHalfDuplexHW);
    report("half_duplex_hw_rejected", !halfDuplexHw.valid());

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
