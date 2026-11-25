// TMC5160 Minimal example: positioning moves with the internal ramp generator.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "SPIConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "TMC5160.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#ifndef TMC5160_SPI_NODE
#define TMC5160_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef TMC5160_CS_GPIOS
#define TMC5160_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(TMC5160_SPI_NODE, cs_gpios, 0)
#endif

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
    const struct device* dev = DEVICE_DT_GET(TMC5160_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = TMC5160_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);  // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    example(connection);
    while (true) k_msleep(1000);
    return 0;
}
