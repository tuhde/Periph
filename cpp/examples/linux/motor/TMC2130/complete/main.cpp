// TMC2130 Complete example: exercise every method in the public API.
// Wiring: SPI Mode 3 (SPI_MODE pin high, SCK idles high), 1 MHz; STEP/DIR/ENN (and DIAG0) on plain GPIO.
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "OutputPinLinux.h"
#include "InputPinLinux.h"
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
    OutputPinLinux en("/dev/gpiochip0", 17);
    OutputPinLinux stp("/dev/gpiochip0", 27);
    OutputPinLinux dir("/dev/gpiochip0", 22);
    InputPinLinux diag("/dev/gpiochip0", 23);
TMC2130Full motor(connection, &stp, &dir, 0.11f, &en, &diag); // Create TMC2130 Full driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin)
                                                     // reads IOIN to verify the chip, writes the default configuration with mirror checks
    if (!motor.ok()) { LOG("TMC2130 not found\n"); return; }

    motor.enable();                                   // Power stage on, () → void
                                                      // drives ENN low, waits 100 ms
    motor.setCurrent(0.8f);                           // Set run current, (rms_a A RMS) → bool
                                                      // converts amperes to the 0–31 scale, picks vsense automatically
    motor.setHoldCurrent(0.2f);                       // Set hold current, (rms_a A RMS) → bool
                                                      // overrides the automatic one-third hold current
    motor.setCurrentDelay(6, 10);                     // Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → bool
                                                      // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    motor.setMicrosteps(32);                          // Set microstep resolution, (n 1–256) → bool
                                                      // writes CHOPCONF.MRES
    motor.setInterpolation(true);                     // Interpolate to 256 microsteps, (enabled) → void
                                                      // sets CHOPCONF.intpol
    motor.setChopperMode(false);                      // Choose chopper, (spreadcycle) → void
                                                      // false enables StealthChop below TPWMTHRS
    motor.setStealthchopThreshold(500);               // Set StealthChop velocity limit, (tpwmthrs 20 bit) → bool
                                                      // faster than this the chip switches to SpreadCycle
    motor.setHighVelocityThreshold(0);                // Set THIGH, (thigh 20 bit) → bool
                                                      // 0 keeps the high-velocity features off
    motor.configureChopper(3, 2, 4, 1);               // Configure SpreadCycle timing, (toff, tbl, hstrt, hend) → bool
                                                      // direct CHOPCONF fields with chm = 0
    motor.configureConstantOffTime(3, 2, 4, 0, false, false); // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → bool
                                                      // chm = 1 with fast decay
    motor.configureChopper(3, 2, 4, 1);               // Back to SpreadCycle, (toff, tbl, hstrt, hend) → bool
                                                      // chm = 0 again
    motor.configureHighVelocity(false, false);        // Behaviour above THIGH, (vhighchm, vhighfs) → void
                                                      // both off
    motor.setChopperSync(0);                          // Set chopSync2, (sync 0–15) → bool
                                                      // 0 = off
    motor.configureStealthchop(true, false, 200, 1, 0); // Configure StealthChop, (autoscale, symmetric, pwm_ampl, pwm_grad, pwm_freq) → bool
                                                      // direct PWMCONF fields
    motor.setFreewheel(0);                            // Set standstill mode, (mode 0–3) → bool
                                                      // PWMCONF.freewheel, only effective when IHOLD is 0
    motor.setVsense(false);                           // Select sense range, (high_sensitivity) → void
                                                      // false keeps the 0.325 V full scale; disables automatic selection
    motor.setInternalRsense(false);                   // Internal sense resistors, (enabled) → void
                                                      // keep false on boards with external resistors
    motor.setAnalogScaling(false);                    // Current reference source, (enabled) → void
                                                      // false uses the internal reference
    motor.setDirectionInverted(false);                // Invert direction, (inverted) → void
                                                      // GCONF.shaft
    motor.configureDiag(false, false, true, false, true, false, false, false); // Route events to DIAG pins, (diag0_error, diag0_otpw, diag0_stall, diag1_stall, diag1_index, diag1_onstate, diag0_pushpull, diag1_pushpull) → void
                                                      // GCONF bits 5–13
    motor.setDoubleEdge(false);                       // Step on both edges, (enabled) → void
                                                      // CHOPCONF.dedge
    motor.setShortProtection(true);                   // Short-to-GND protection, (short_gnd) → void
                                                      // true keeps the protection enabled
    motor.setSmallHysteresis(false);                  // Comparison hysteresis, (enabled) → void
                                                      // false = 1/16
    motor.setEmergencyStop(false);                    // DCIN emergency stop, (enabled) → void
                                                      // GCONF.stop_enable
    motor.setStallThreshold(0);                       // Set StallGuard2 threshold, (sgt -64..63) → bool
                                                      // lower = more sensitive
    motor.setStallguardFilter(false);                 // StallGuard2 filter, (enabled) → void
                                                      // COOLCONF.sfilt
    motor.setCoolstepThreshold(1000);                 // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → bool
                                                      // active while TCOOLTHRS >= TSTEP >= THIGH
    motor.configureCoolstep(5, 2, 1, 1, 0);           // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → bool
                                                      // semin = 0 would disable CoolStep
    motor.move(200, 150.0f);                          // Move, (full_steps, full_steps_per_s full steps/s) → bool
                                                      // blocking, software-timed STEP pulses
    motor.step(400, 0.001f);                          // Pulse the STEP pin, (count, delay_s s) → bool
                                                      // negative counts reverse via DIR

    LOG("position=%d\n", (int)motor.microstepPosition()); // Read microstep counter, () → uint16_t 0–1023
                                                      // MSCNT
    TMC2130Full::CoilCurrents cc = motor.readCoilCurrents(); // Read coil currents, () → CoilCurrents {int16_t curA, curB}
                                                      // signed (CUR_A, CUR_B) from MSCURACT
    LOG("currents=%d,%d\n", (int)cc.curA, (int)cc.curB);
    LOG("tstep=%u\n", (unsigned)motor.readTstep());   // Read step interval, () → uint32_t clocks
                                                      // time between 1/256 microsteps
    LOG("scale=%d\n", (int)motor.readCurrentScale()); // Read actual current scale, () → uint8_t 0–31
                                                      // DRV_STATUS.CS_ACTUAL
    LOG("sg_result=%d\n", (int)motor.readStallguard()); // Read StallGuard2 result, () → uint16_t 0–1023
                                                      // SG_RESULT, 0 = highest load
    LOG("stalled=%d\n", (int)motor.isStalled());      // Read stall flag, () → bool
                                                      // DRV_STATUS.StallGuard
    LOG("diag0=%d\n", (int)motor.readDiag(0));        // Read DIAG0 pin, (n 0 or 1) → bool
                                                      // needs the diag pin to be wired
    LOG("pwm_scale=%d\n", (int)motor.readPwmScale()); // Read PWM scaler, () → uint8_t 0–255
                                                      // PWM_SCALE
    LOG("lost_steps=%u\n", (unsigned)motor.readLostSteps()); // Read skipped DcStep steps, () → uint32_t
                                                      // LOST_STEPS
    TMC2130Full::Status st = motor.readStatus();      // Read driver status, () → Status
                                                      // decoded DRV_STATUS
    LOG("stst=%d ot=%d\n", (int)st.stst, (int)st.ot);
    LOG("standstill=%d\n", (int)motor.isStandstill()); // Read standstill flag, () → bool
                                                      // DRV_STATUS.stst
    LOG("fault=%d\n", (int)motor.readFault());        // Read fault bitmask, () → uint8_t
                                                      // FAULT_* constants, 0 = none
    TMC2130Full::GlobalStatus gs = motor.readGlobalStatus(); // Read GSTAT, () → GlobalStatus
                                                      // reset, drv_err, uv_cp; the chip clears them on read
    LOG("reset=%d drv_err=%d uv_cp=%d\n", (int)gs.reset, (int)gs.drvErr, (int)gs.uvCp);
    TMC2130Full::Ioin io = motor.readIoin();          // Read IOIN, () → Ioin {raw, step, dir, dcen, dcin, enn, dco, version}
                                                      // raw word, pin states and silicon version
    LOG("version=0x%02X\n", (unsigned)io.version);
    LOG("gconf=0x%08X\n", (unsigned)motor.readRegister(0x00)); // Read a raw register, (reg) → uint32_t
                                                      // any readable register
    motor.writeRegister(0x13, 500, true);             // Write a raw register, (reg, value, verify=false) → bool
                                                      // here TPWMTHRS again, with the mirror check
    motor.setDcstep(0, 0, 0);                         // Configure DcStep, (vdcmin, dc_time, dc_sg) → bool
                                                      // all zero keeps DcStep off
    motor.setDirectMode(true);                        // Direct coil-current mode, (enabled) → void
                                                      // GCONF.direct_mode
    motor.setCoilCurrents(100, 0);                    // Write XDIRECT, (coil_a, coil_b -255..255) → bool
                                                      // scaled by IHOLD
    SLEEP_MS(500);
    motor.setDirectMode(false);                       // Back to STEP/DIR, (enabled) → void
                                                      // GCONF.direct_mode = 0
    { static const uint32_t table[8] = {1, 2, 3, 4, 5, 6, 7, 8}; if (false) motor.writeMicrostepTable(table, 0, 0x00F70000UL); } // Program the microstep table, (mslut[8], mslutsel, mslutstart) → void
                                                      // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0 (placeholder words, never executed here)
    motor.disable();                                  // Power stage off, () → void
                                                      // drives ENN high; the motor freewheels
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 3, 1000000);  // Create SPI connection, (bus=0, device=0, mode=3, max_speed_hz=1e6) → SPIConnectionLinux
    example(connection);
    return 0;
}
