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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { LOG("PASS %s\n", label); passed++; }
    else      { LOG("FAIL %s\n", label); failed++; }
}

static void run_tests(Connection& connection) {
    static const struct gpio_dt_spec en_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 10, .dt_flags = 0 };
    OutputPinZephyr en(en_spec);
    TMC5160Full motor(connection, 0.075f, &en);  // Create TMC5160 Full driver, (connection, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin)
    check_true(motor.ok(), "init_ok");
    if (!motor.ok()) { LOG("===DONE: %d passed, %d failed===\n", passed, failed + 1); return; }

    TMC5160Full::Ioin io = motor.readIoin();                // Read IOIN, () → Ioin
    check_true(io.version == 0x30, "ioin_version");
    check_true(motor.writeRegister(0x13, 600, true), "verified_write");  // Write a raw register, (reg, value, verify) → bool
    motor.setMicrosteps(16);                                // Set microstep resolution, (n 1–256) → bool
    check_true(((motor.readRegister(0x6C) >> 24) & 0xF) == 4, "mres_readback");
    check_true((motor.readRegister(0x00) & 0x4) == 0x4, "gconf_readback");
    motor.setMicrosteps(256);
    motor.enable();                                         // Power stage on, () → void
    motor.setPosition(0.0f);                                // Define position, (position full steps) → bool
    motor.moveTo(100.0f);                                   // Start positioning move, (position full steps) → bool
    check_true(motor.waitUntilReached(), "move_reached");   // Wait for the ramp generator, (timeout_s=0 s) → bool
    float pos = motor.position();                           // Read XACTUAL, () → float full steps
    check_true(pos > 99.0f && pos < 101.0f, "position_readback");
    check_true(motor.readFault() == 0, "no_fault");
    check_true(motor.readStallguard() <= 1023, "sg_result_range");
    motor.disable();                                        // Power stage off, () → void
    check_true(motor.readStatus().csActual <= 31, "status_decoded");

    LOG("===DONE: %d passed, %d failed===\n", passed, failed);
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
    run_tests(connection);
    while (true) k_msleep(1000);
    return 0;
}
