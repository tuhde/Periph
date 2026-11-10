#include <stdio.h>
#include <hardware/gpio.h>
#include <hardware/spi.h>
#include "pico/stdlib.h"
#include "SPIConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "TMC2130.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { LOG("PASS %s\n", label); passed++; }
    else      { LOG("FAIL %s\n", label); failed++; }
}

static void run_tests(Connection& connection) {
    OutputPinPicoSDK en(10);
    OutputPinPicoSDK stp(11);
    OutputPinPicoSDK dir(12);
    TMC2130Full motor(connection, &stp, &dir, 0.11f, &en);  // Create TMC2130 Full driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin)
    check_true(motor.ok(), "init_ok");
    if (!motor.ok()) { LOG("===DONE: %d passed, %d failed===\n", passed, failed + 1); return; }

    TMC2130Full::Ioin io = motor.readIoin();                // Read IOIN, () → Ioin
    check_true(io.version == 0x11, "ioin_version");
    check_true(motor.writeRegister(0x13, 600, true), "verified_write");  // Write a raw register, (reg, value, verify) → bool
    motor.setCurrent(0.5f);                                 // Set run current, (rms_a A RMS) → bool
    motor.setMicrosteps(16);                                // Set microstep resolution, (n 1–256) → bool
    check_true(((motor.readRegister(0x6C) >> 24) & 0xF) == 4, "mres_readback");
    check_true((motor.readRegister(0x00) & 0x4) == 0x4, "gconf_readback");
    motor.enable();                                         // Power stage on, () → void
    motor.move(50, 100.0f);                                 // Move, (full_steps, full_steps_per_s) → bool
    check_true(motor.readFault() == 0, "no_fault");
    check_true(motor.readTstep() < 0x100000UL, "tstep_readable");
    motor.setStallThreshold(0);                             // Set StallGuard2 threshold, (sgt -64..63) → bool
    check_true(motor.readStallguard() <= 1023, "sg_result_range");
    motor.disable();                                        // Power stage off, () → void
    check_true(motor.readStatus().csActual <= 31, "status_decoded");

    LOG("===DONE: %d passed, %d failed===\n", passed, failed);
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
    run_tests(connection);
    while (true) sleep_ms(1000);
    return 0;
}
