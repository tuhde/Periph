#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC8159.h"
#include "uc8159_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

static uint8_t frameBuffer[100801];

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
    UC8159Full<DisplayConnectionPicoSDK> chip(connection, frameBuffer, sizeof(frameBuffer));
    uc8159Checks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
