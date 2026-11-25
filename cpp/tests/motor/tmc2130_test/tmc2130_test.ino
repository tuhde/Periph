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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { LOG("PASS %s\n", label); passed++; }
    else      { LOG("FAIL %s\n", label); failed++; }
}

static void run_tests(Connection& connection) {
    OutputPinArduino en(4);
    OutputPinArduino stp(5);
    OutputPinArduino dir(6);
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

void setup() {
    Serial.begin(115200);
    SPI.begin();
    SPISettings settings(1000000, MSBFIRST, SPI_MODE3);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);  // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    run_tests(connection);
}

void loop() {}
