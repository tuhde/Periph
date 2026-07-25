#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPITransportPicoSDK.h"
#include "MFRC522.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 17;

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPITransportPicoSDK transport(spi0, CS_PIN);
    MFRC522Full mfrc(transport);

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);
    check_true(chip_type == 0x09, "chip_type");
    check_true(mfrc.self_test(), "self_test");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
