// TMC5160 Minimal example: positioning moves with the internal ramp generator.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
#include "TMC5160.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) usleep((ms) * 1000)

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
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

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 3, 1000000);  // Create SPI connection, (bus=0, device=0, mode=3, max_speed_hz=1e6) → SPIConnectionLinux
    example(connection);
    return 0;
}
