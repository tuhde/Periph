// TMC2209 Demo: sensorless homing of a linear axis with StallGuard4 and CoolStep.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).

#include <Arduino.h>
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

static void example(Connection& connection) {
    OutputPinArduino en(4);
    TMC2209Full motor(connection, 0.11f, 0, &en);           // Create TMC2209 Full driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin)
    if (!motor.ok()) { LOG("TMC2209 not found\n"); return; }

    const int MICROSTEPS = 16;
    const int HOMING_SPEED = 200;
    const int SGTHRS = 60;

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    TMC2209Full::Ioin io = motor.readIoin();             // Read IOIN, () → Ioin
    LOG("version 0x%02X, IFCNT %d\n", (unsigned)io.version, (int)motor.readIfcnt());  // readIfcnt() → uint8_t
    motor.setCurrent(0.8f);                              // Set run current, (rms_a A RMS) → bool
    motor.setMicrosteps(MICROSTEPS);                     // Set microstep resolution, (n 1–256) → bool
    motor.enable();                                      // Power stage on, () → void

    // --- Arm StallGuard4 for the homing speed ---
    // StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
    // is set just below the TSTEP of the homing speed so detection is active.
    motor.setStallThreshold(SGTHRS);                     // Set StallGuard4 threshold, (sgthrs 0–255) → void
    motor.setCoolstepThreshold(12000000UL / (HOMING_SPEED * 256 / 2));  // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → bool

    // --- Drive toward the end stop ---
    // The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
    // the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
    motor.run((float)HOMING_SPEED);                      // Spin with the internal generator, (steps_per_s full steps/s) → void
    SLEEP_MS(500);
    uint16_t sg;
    for (;;) {
        sg = motor.readStallguard();                     // Read StallGuard4 result, () → uint16_t 0–510
        LOG("SG_RESULT %d\n", (int)sg);
        if (sg <= 2 * SGTHRS) break;

        // --- Stop on any driver fault ---
        // Short circuits and over-temperature latch until the driver is
        // disabled, so the demo shuts everything down and reports it.
        uint8_t fault = motor.readFault();               // Read fault bitmask, () → uint8_t
        if (fault) {
            motor.stop();                                // Stop the motor, () → void
            motor.disable();                             // Power stage off, () → void
            LOG("fault 0x%02X\n", (unsigned)fault);
            return;
        }
        SLEEP_MS(100);
    }

    // --- End stop found: stop and back off ---
    // Reversing for 2 s moves the axis clear of the stop.
    motor.stop();                                        // Stop the motor, () → void
    LOG("stalled at SG_RESULT %d\n", (int)sg);
    motor.run(-(float)HOMING_SPEED);                     // Spin with the internal generator, (steps_per_s full steps/s) → void
    SLEEP_MS(2000);
    motor.stop();                                        // Stop the motor, () → void

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configureCoolstep(5, 2, 1, 1, 0);              // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → bool
    motor.run((float)HOMING_SPEED);                      // Spin with the internal generator, (steps_per_s full steps/s) → void
    for (int i = 0; i < 10; i++) {
        SLEEP_MS(500);
        LOG("CS_ACTUAL %d\n", (int)motor.readCurrentScale());  // Read actual current scale, () → uint8_t 0–31
    }
    motor.stop();                                        // Stop the motor, () → void

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    LOG("temperature flags %d\n", (int)motor.readTemperatureFlags());  // Read temperature flags, () → uint8_t 0–15
    TMC2209Full::GlobalStatus gs = motor.readGlobalStatus();  // Read GSTAT, (clear=false) → GlobalStatus
    LOG("reset=%d drv_err=%d uv_cp=%d\n", (int)gs.reset, (int)gs.drvErr, (int)gs.uvCp);
    motor.disable();                                     // Power stage off, () → void
}

void setup() {
    Serial.begin(115200);
    Serial1.begin(115200);
    UARTConnection connection(Serial1);
    example(connection);
}

void loop() {}
