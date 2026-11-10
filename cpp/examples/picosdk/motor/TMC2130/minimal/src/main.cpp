// TMC2130 Minimal example: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high, SCK idles high), 1 MHz; STEP/DIR/ENN (and DIAG0) on plain GPIO.
#include <stdio.h>
#include <hardware/gpio.h>
#include <hardware/spi.h>
#include "pico/stdlib.h"
#include "SPIConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "TMC2130.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

static void example(Connection& connection) {
    OutputPinPicoSDK stp(11);
    OutputPinPicoSDK dir(12);
TMC2130Minimal motor(connection, &stp, &dir);         // Create TMC2130 driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin=nullptr)
    if (!motor.ok()) { LOG("TMC2130 not found\n"); return; }

    motor.setCurrent(0.8f);                           // Set run current, (rms_a A RMS) → bool
    motor.enable();                                   // Power stage on, () → void
    for (int i = 0; i < 2; i++) {
        motor.move(i == 0 ? 400 : -400, 200.0f);      // Move, (full_steps, full_steps_per_s full steps/s) → bool
        SLEEP_MS(500);
        LOG("standstill=%d fault=%d\n", (int)motor.isStandstill(), (int)motor.readFault());  // isStandstill() → bool, readFault() → uint8_t bitmask
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
