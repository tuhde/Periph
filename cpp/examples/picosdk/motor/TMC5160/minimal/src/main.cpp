// TMC5160 Minimal example: positioning moves with the internal ramp generator.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.
#include <stdio.h>
#include <hardware/gpio.h>
#include <hardware/spi.h>
#include "pico/stdlib.h"
#include "SPIConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "TMC5160.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

static void example(Connection& connection) {
TMC5160Minimal motor(connection);                     // Create TMC5160 driver, (connection, r_sense_ohm=0.075 Ω, en_pin=nullptr)
    if (!motor.ok()) { LOG("TMC5160 not found\n"); return; }

    motor.setCurrent(1.0f);                           // Set run current, (rms_a A RMS) → bool
    motor.enable();                                   // Power stage on, () → void
    for (int i = 0; i < 2; i++) {
        motor.moveTo(i == 0 ? 400.0f : 0.0f);         // Start positioning move, (position full steps) → bool
        motor.waitUntilReached();                     // Wait for the ramp generator, (timeout_s=0 s, 0 = estimate) → bool
        LOG("position=%d fault=%d\n", (int)motor.position(), (int)motor.readFault());  // position() → float full steps, readFault() → uint8_t bitmask
    }
    motor.disable();                                  // Power stage off, () → void
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    // SPI0 on GP19 (MOSI) / GP16 (MISO) / GP18 (SCK), CS on GP5; Mode 3 (CPOL=1, CPHA=1).
    spi_init(spi0, 1000000);
    spi_set_format(spi0, 8, SPI_CPOL_1, SPI_CPHA_1, SPI_MSB_FIRST);
    gpio_set_function(19, GPIO_FUNC_SPI);
    gpio_set_function(16, GPIO_FUNC_SPI);
    gpio_set_function(18, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, 5);  // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    example(connection);
    while (true) sleep_ms(1000);
    return 0;
}
