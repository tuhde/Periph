// TMC5160 Demo example: pick-and-place axis with the internal ramp generator and StallGuard2.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.

#include <Arduino.h>
#include <SPI.h>
#include <Periph.h>

static void LOG(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define SLEEP_MS(ms) delay(ms)

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

static void example(Connection& connection) {
    OutputPinArduino en(4);
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

void setup() {
    Serial.begin(115200);
    SPI.begin();
    SPISettings settings(1000000, MSBFIRST, SPI_MODE3);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);  // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    example(connection);
}

void loop() {}
