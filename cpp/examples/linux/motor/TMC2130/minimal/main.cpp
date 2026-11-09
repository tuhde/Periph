// TMC2130 Minimal example: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high, SCK idles high), 1 MHz; STEP/DIR/ENN (and DIAG0) on plain GPIO.
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "TMC2130.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) usleep((ms) * 1000)

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static void example(Connection& connection) {
    OutputPinLinux stp("/dev/gpiochip0", 27);
    OutputPinLinux dir("/dev/gpiochip0", 22);
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

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 3, 1000000);  // Create SPI connection, (bus=0, device=0, mode=3, max_speed_hz=1e6) → SPIConnectionLinux
    example(connection);
    return 0;
}
