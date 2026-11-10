// TMC2208 Demo: silent desk-fan / camera-slider controller using the internal step generator.
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
    TMC2208Full motor(connection, 0.11f, &en);           // Create TMC2208 Full driver, (connection, r_sense_ohm=0.11 Ω, en_pin)
    if (!motor.ok()) { LOG("TMC2208 not found\n"); return; }

    const int MICROSTEPS = 16;
    const int SPREAD_ABOVE = 400;

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    TMC2208Full::Ioin io = motor.readIoin();             // Read IOIN, () → Ioin
    LOG("version 0x%02X, IFCNT %d\n", (unsigned)io.version, (int)motor.readIfcnt());  // readIfcnt() → uint8_t
    motor.setCurrent(0.8f);                              // Set run current, (rms_a A RMS) → bool
    motor.setMicrosteps(MICROSTEPS);                     // Set microstep resolution, (n 1–256) → bool
    motor.enable();                                      // Power stage on, () → void

    // --- Quiet at low speed, strong at high speed ---
    // StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
    // change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
    motor.setStealthchopThreshold(12000000UL / (SPREAD_ABOVE * 256));  // Set StealthChop velocity limit, (tpwmthrs 20 bit) → void

    for (int sign = 1; sign >= -1; sign -= 2) {
        // --- Ramp up, hold, ramp down in 100 steps/s increments ---
        // VACTUAL has no acceleration of its own, so the demo steps the speed
        // and reports whether the chip is in StealthChop or SpreadCycle.
        for (int phase = 0; phase < 2; phase++) {
            for (int k = 0; k <= 6; k++) {
                int speed = phase == 0 ? k * 100 : 600 - k * 100;
                motor.run((float)(sign * speed));        // Spin with the internal generator, (steps_per_s full steps/s) → void
                SLEEP_MS(500);
                TMC2208Full::Status st = motor.readStatus();  // Read driver status, () → Status
                LOG("%4d steps/s  %s  CS=%d\n", speed, st.stealth ? "stealth" : "spread", (int)st.csActual);

                // --- Stop on any driver fault ---
                // Short circuits and over-temperature latch until the driver is
                // disabled, so the demo shuts everything down and reports it.
                uint8_t fault = motor.readFault();       // Read fault bitmask, () → uint8_t
                if (fault) {
                    motor.stop();                        // Stop the motor, () → void
                    motor.disable();                     // Power stage off, () → void
                    LOG("fault 0x%02X\n", (unsigned)fault);
                    return;
                }
            }
            if (phase == 0) SLEEP_MS(3000);
        }
        motor.stop();                                    // Stop the motor, () → void
        SLEEP_MS(1000);
    }

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    LOG("temperature flags %d\n", (int)motor.readTemperatureFlags());  // Read temperature flags, () → uint8_t 0–15
    TMC2208Full::GlobalStatus gs = motor.readGlobalStatus();  // Read GSTAT, (clear=false) → GlobalStatus
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
