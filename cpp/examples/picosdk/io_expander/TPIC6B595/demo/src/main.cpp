// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
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
#ifndef TPIC6B595_SRCLR
#define TPIC6B595_SRCLR 16
#endif
#ifndef TPIC6B595_G
#define TPIC6B595_G     15
#endif

static constexpr uint8_t NUM_DEVICES = 2;
static constexpr uint8_t NUM_OUTPUTS = NUM_DEVICES * 8;

int main() {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(TPIC6B595_SPI, 1000 * 1000);
    gpio_set_function(TPIC6B595_SCK,  GPIO_FUNC_SPI);
    gpio_set_function(TPIC6B595_MOSI, GPIO_FUNC_SPI);

    SiPoConnectionPicoSDK connection(TPIC6B595_SPI, TPIC6B595_RCK,
                                     TPIC6B595_SRCLR, TPIC6B595_G); // Create SiPo connection, (spi, rck, srclr, g)
    TPIC6B595Full<SiPoConnectionPicoSDK> chip(connection, NUM_DEVICES); // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                          // two cascaded devices — 16 outputs total; outputs start OFF

    int8_t position = 0;
    int8_t direction = 1;
    uint8_t sweep_count = 0;
    constexpr uint8_t BLANK_EVERY = 3;
    constexpr uint32_t BLANK_MS = 500;

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        uint8_t bytes_[NUM_DEVICES] = {0, 0};
        uint8_t port = position / 8;
        uint8_t bit  = position % 8;
        bytes_[port] = (uint8_t)(1u << bit);
        chip.write_all(bytes_, NUM_DEVICES);                              // Write all device bytes, (values=uint8_t*, len=2) → void

        printf("position=%d  bytes=[0x%02X, 0x%02X]\n",
               (int)position, bytes_[0], bytes_[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count++;
        if (sweep_count % BLANK_EVERY == 0) {
            chip.set_output_enable(false);                                // Force every output off via G, (enabled=false) → int
                                                                          // the chase pattern's shadow state is preserved
            printf("  blanked via G for %u ms\n", (unsigned)BLANK_MS);
            sleep_ms(BLANK_MS);
            chip.set_output_enable(true);                                 // Re-enable outputs, (enabled=true) → int
                                                                          // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if (position >= (int8_t)(NUM_OUTPUTS - 1) || position <= 0) {
            direction = -direction;
            sleep_ms(100);
        } else {
            sleep_ms(80);
        }
    }
    return 0;
}
