#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "SiPoConnectionPicoSDK.h"
#include "TPIC6B595.h"

#ifndef TPIC6B595_SPI
#define TPIC6B595_SPI spi0
#endif
#ifndef TPIC6B595_SCK
#define TPIC6B595_SCK  18
#endif
#ifndef TPIC6B595_MOSI
#define TPIC6B595_MOSI 19
#endif
#ifndef TPIC6B595_RCK
#define TPIC6B595_RCK  17
#endif

int main() {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(TPIC6B595_SPI, 1000 * 1000);
    gpio_set_function(TPIC6B595_SCK,  GPIO_FUNC_SPI);
    gpio_set_function(TPIC6B595_MOSI, GPIO_FUNC_SPI);

    SiPoConnectionPicoSDK connection(TPIC6B595_SPI, TPIC6B595_RCK);   // Create SiPo connection, (spi, rck, srclr=-1, g=-1)
    TPIC6B595Minimal<SiPoConnectionPicoSDK> chip(connection);          // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                          // initialises every output to OFF (shadow zeroed, latched once)

    auto p0 = chip.pin(0);                                                // Get pin proxy, (n=0) → IOExpanderPin
    auto p7 = chip.pin(7);                                                // Get pin proxy, (n=7) → IOExpanderPin

    while (true) {
        p0.high();                                                        // Set DMOS output ON, () → void
        p7.low();                                                         // Set DMOS output OFF, () → void
        sleep_ms(500);
        p0.low();                                                         // Set DMOS output OFF, () → void
        p7.high();                                                        // Set DMOS output ON, () → void
        sleep_ms(500);
    }
    return 0;
}
