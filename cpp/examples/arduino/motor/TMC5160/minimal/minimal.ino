// TMC5160 Minimal example: positioning moves with the internal ramp generator.
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

void setup() {
    Serial.begin(115200);
    SPI.begin();
    SPISettings settings(1000000, MSBFIRST, SPI_MODE3);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);  // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    example(connection);
}

void loop() {}
