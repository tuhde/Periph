// TMC2130 Demo example: sensorless homing of a linear axis with StallGuard2 and CoolStep.
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
    OutputPinPicoSDK en(10);
    OutputPinPicoSDK stp(11);
    OutputPinPicoSDK dir(12);
TMC2130Full motor(connection, &stp, &dir, 0.11f, &en); // Create TMC2130 Full driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin)
    if (!motor.ok()) { LOG("TMC2130 not found\n"); return; }

    const int HOMING_SPEED = 150;
    const int BURST = 20;

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
    // motion is attempted.
    TMC2130Full::Ioin io = motor.readIoin();          // Read IOIN, () → Ioin
    LOG("version 0x%02X\n", (unsigned)io.version);
    motor.setCurrent(0.8f);                           // Set run current, (rms_a A RMS) → bool
    motor.setMicrosteps(16);                          // Set microstep resolution, (n 1–256) → bool
    motor.enable();                                   // Power stage on, () → void

    // --- Arm StallGuard2 for the homing speed ---
    // StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
    // just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
    motor.setChopperMode(true);                       // Choose chopper, (spreadcycle) → void
    motor.setStallThreshold(0);                       // Set StallGuard2 threshold, (sgt -64..63) → bool
    motor.setCoolstepThreshold(12000000UL / (HOMING_SPEED * 256 / 2)); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → bool

    // --- Drive toward the end stop in short bursts ---
    // The first burst is spin-up, where SG_RESULT is not yet valid; after each
    // burst the demo reads SG_RESULT until the stall flag is set.
    uint16_t history[5] = {0};
    int count = 0;
    motor.move(BURST, (float)HOMING_SPEED);           // Move, (full_steps, full_steps_per_s full steps/s) → bool
    for (;;) {
        motor.move(BURST, (float)HOMING_SPEED);       // Move, (full_steps, full_steps_per_s full steps/s) → bool
        uint16_t sg = motor.readStallguard();         // Read StallGuard2 result, () → uint16_t 0–1023
        history[count++ % 5] = sg;
        LOG("SG_RESULT %d\n", (int)sg);
        if (motor.isStalled()) break;                 // Read stall flag, () → bool

        // --- Stop on any driver fault ---
        // Short circuits and over-temperature latch until the driver is
        // disabled, so the demo shuts everything down and reports it.
        uint8_t fault = motor.readFault();            // Read fault bitmask, () → uint8_t
        if (fault) {
            motor.disable();                          // Power stage off, () → void
            LOG("fault 0x%02X\n", (unsigned)fault);
            return;
        }
    }

    // --- End stop found: report and back off ---
    // The last few SG_RESULT values show how the load built up before the stall.
    for (int i = 0; i < 5 && i < count; i++) LOG("history %d\n", (int)history[i]);
    motor.move(-200, (float)HOMING_SPEED);            // Move, (full_steps, full_steps_per_s full steps/s) → bool

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configureCoolstep(5, 2, 1, 1, 0);           // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → bool
    for (int i = 0; i < 10; i++) {
        motor.move(20, (float)HOMING_SPEED);          // Move, (full_steps, full_steps_per_s full steps/s) → bool
        LOG("CS_ACTUAL %d\n", (int)motor.readCurrentScale());  // Read actual current scale, () → uint8_t 0–31
    }

    // --- Shut down and report ---
    // GSTAT shows whether the run stressed the driver; the chip clears it on read.
    TMC2130Full::Status st = motor.readStatus();      // Read driver status, () → Status
    TMC2130Full::GlobalStatus gs = motor.readGlobalStatus(); // Read GSTAT, () → GlobalStatus
    LOG("stst=%d reset=%d drv_err=%d\n", (int)st.stst, (int)gs.reset, (int)gs.drvErr);
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
