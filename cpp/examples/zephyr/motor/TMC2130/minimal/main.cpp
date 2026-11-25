// TMC2130 Minimal example: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high, SCK idles high), 1 MHz; STEP/DIR/ENN (and DIAG0) on plain GPIO.
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "SPIConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "TMC2130.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#ifndef TMC2130_SPI_NODE
#define TMC2130_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef TMC2130_CS_GPIOS
#define TMC2130_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(TMC2130_SPI_NODE, cs_gpios, 0)
#endif

static void example(Connection& connection) {
    static const struct gpio_dt_spec stp_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 11, .dt_flags = 0 };
    OutputPinZephyr stp(stp_spec);
    static const struct gpio_dt_spec dir_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 12, .dt_flags = 0 };
    OutputPinZephyr dir(dir_spec);
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
    const struct device* dev = DEVICE_DT_GET(TMC2130_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = TMC2130_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);  // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    example(connection);
    while (true) k_msleep(1000);
    return 0;
}
