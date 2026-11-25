// TMC5160 Demo example: pick-and-place axis with the internal ramp generator and StallGuard2.
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
    static const struct gpio_dt_spec en_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 10, .dt_flags = 0 };
    OutputPinZephyr en(en_spec);
TMC5160Full motor(connection, 0.075f, &en);           // Create TMC5160 Full driver, (connection, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin)
    if (!motor.ok()) { LOG("TMC5160 not found\n"); return; }

    // --- Pick-and-place axis: smooth trapezoid between two stations ---
    // The internal ramp generator accelerates at 400 full steps/s² up to 600 full steps/s;
    // StealthChop keeps the axis quiet and StallGuard2 reports a crash against an end stop.
    motor.setCurrent(1.2f);                           // Set run current, (rms_a A RMS) → bool
    motor.setSpeed(600.0f);                           // Set VMAX, (full_steps_per_s full steps/s) → bool
    motor.setAcceleration(400.0f);                    // Set acceleration, (full_steps_per_s2 full steps/s²) → bool
    motor.setStallThreshold(2);                       // Set StallGuard2 threshold, (sgt -64..63) → bool
    motor.enable();                                   // Power stage on, () → void
    motor.setPosition(0.0f);                          // Define position, (position full steps) → bool

    // --- Shuttle between the stations and report each stop ---
    // Each stop prints the position and the StallGuard2 reading; a driver fault
    // shuts everything down and ends the run.
    const float stations[4] = {1000.0f, 0.0f, 1000.0f, 0.0f};
    for (int i = 0; i < 4; i++) {
        motor.moveTo(stations[i]);                    // Start positioning move, (position full steps) → bool
        motor.waitUntilReached();                     // Wait for the ramp generator, (timeout_s=0 s) → bool
        float pos = motor.position();                 // Read XACTUAL, () → float full steps
        uint16_t sg = motor.readStallguard();         // Read StallGuard2 result, () → uint16_t 0–1023
        LOG("at %d (SG %d)\n", (int)pos, (int)sg);
        uint8_t fault = motor.readFault();            // Read fault bitmask, () → uint8_t
        if (fault) {
            motor.disable();                          // Power stage off, () → void
            LOG("fault 0x%02X\n", (unsigned)fault);
            return;
        }
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
