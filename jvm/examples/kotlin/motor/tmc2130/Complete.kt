///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

// TMC2130 complete: exercise every method in the public API.
// Wiring: SPI Mode 3 (SPI_MODE pin high); STEP/DIR/ENN on GPIO 27/22/17 (DIAG0 on GPIO 23 for the complete example).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.SysfsInputPin
import it.uhde.periph.chips.motor.TMC2130Full

fun main() {
    val stepPin = SysfsOutputPin(27)                                              // create STEP pin, (gpio=27) → OutputPin
    val dirPin = SysfsOutputPin(22)                                               // create DIR pin, (gpio=22) → OutputPin
    val enPin = SysfsOutputPin(17)                                                // create ENN pin, (gpio=17) → OutputPin
    val diag0 = SysfsInputPin(23)                                                 // create DIAG0 pin, (gpio=23) → InputPin
    SPIConnection(System.getenv("SPI_BUS")?.toIntOrNull() ?: 0, System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0, 3, 1_000_000).use { connection ->// open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
        val motor = TMC2130Full(connection, stepPin, dirPin, 0.11, enPin, diag0, null)// create Full driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin, diag0, diag1) → TMC2130Full
        motor.enable()                                                                // power stage on, () → void
                                                                                      // drives ENN low, waits 100 ms
        motor.setCurrent(0.8)                                                         // set run current, (rmsA A RMS) → void
                                                                                      // converts amperes to the 0–31 scale, picks vsense automatically
        motor.setHoldCurrent(0.2)                                                     // set hold current, (rmsA A RMS) → void
                                                                                      // overrides the automatic one-third hold current
        motor.setCurrentDelay(6, 10)                                                  // set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → void
                                                                                      // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
        motor.setMicrosteps(32)                                                       // set microstep resolution, (n 1–256) → void
                                                                                      // writes CHOPCONF.MRES
        motor.setInterpolation(true)                                                  // interpolate to 256 microsteps, (enabled) → void
                                                                                      // sets CHOPCONF.intpol
        motor.setChopperMode(false)                                                   // choose chopper, (spreadcycle) → void
                                                                                      // false enables StealthChop below TPWMTHRS
        motor.setStealthchopThreshold(500)                                            // set StealthChop velocity limit, (tpwmthrs 20 bit) → void
                                                                                      // faster than this the chip switches to SpreadCycle
        motor.setHighVelocityThreshold(0)                                             // set THIGH, (thigh 20 bit) → void
                                                                                      // 0 keeps the high-velocity features off
        motor.configureChopper(3, 2, 4, 1)                                            // configure SpreadCycle timing, (toff, tbl, hstrt, hend) → void
                                                                                      // direct CHOPCONF fields with chm = 0
        motor.configureConstantOffTime(3, 2, 4, 0, false, false)                      // configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → void
                                                                                      // chm = 1 with fast decay
        motor.configureChopper(3, 2, 4, 1)                                            // back to SpreadCycle, (toff, tbl, hstrt, hend) → void
                                                                                      // chm = 0 again
        motor.configureHighVelocity(false, false)                                     // behaviour above THIGH, (vhighchm, vhighfs) → void
                                                                                      // both off
        motor.setChopperSync(0)                                                       // set chopSync2, (sync 0–15) → void
                                                                                      // 0 = off
        motor.configureStealthchop(true, false, 200, 1, 0)                            // configure StealthChop, (autoscale, symmetric, pwmAmpl, pwmGrad, pwmFreq) → void
                                                                                      // direct PWMCONF fields
        motor.setFreewheel(0)                                                         // set standstill mode, (mode 0–3) → void
                                                                                      // PWMCONF.freewheel, only effective when IHOLD is 0
        motor.setVsense(false)                                                        // select sense range, (highSensitivity) → void
                                                                                      // false keeps the 0.325 V full scale; disables automatic selection
        motor.setInternalRsense(false)                                                // internal sense resistors, (enabled) → void
                                                                                      // keep false on boards with external resistors
        motor.setAnalogScaling(false)                                                 // current reference source, (enabled) → void
                                                                                      // false uses the internal reference
        motor.setDirectionInverted(false)                                             // invert direction, (inverted) → void
                                                                                      // GCONF.shaft
        motor.configureDiag(false, false, true, false, true, false, false, false)     // route events to DIAG pins, (diag0Error, diag0Otpw, diag0Stall, diag1Stall, diag1Index, diag1Onstate, diag0Pushpull, diag1Pushpull) → void
                                                                                      // GCONF bits 5–13
        motor.setDoubleEdge(false)                                                    // step on both edges, (enabled) → void
                                                                                      // CHOPCONF.dedge
        motor.setShortProtection(true)                                                // short-to-GND protection, (shortGnd) → void
                                                                                      // true keeps the protection enabled
        motor.setSmallHysteresis(false)                                               // comparison hysteresis, (enabled) → void
                                                                                      // false = 1/16
        motor.setEmergencyStop(false)                                                 // DCIN emergency stop, (enabled) → void
                                                                                      // GCONF.stop_enable
        motor.setStallThreshold(0)                                                    // set StallGuard2 threshold, (sgt -64..63) → void
                                                                                      // lower = more sensitive
        motor.setStallguardFilter(false)                                              // StallGuard2 filter, (enabled) → void
                                                                                      // COOLCONF.sfilt
        motor.setCoolstepThreshold(1000)                                              // set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → void
                                                                                      // active while TCOOLTHRS >= TSTEP >= THIGH
        motor.configureCoolstep(5, 2, 1, 1, 0)                                        // configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → void
                                                                                      // semin = 0 would disable CoolStep
        motor.move(200, 150.0)                                                        // move, (fullSteps, fullStepsPerS full steps/s) → void
                                                                                      // blocking, software-timed STEP pulses
        motor.step(400, 0.001)                                                        // pulse the STEP pin, (count, delayS s) → void
                                                                                      // negative counts reverse via DIR
        println("position " + motor.microstepPosition())                              // read microstep counter, () → int 0–1023
                                                                                      // MSCNT
        println("currents " + motor.readCoilCurrents())                               // read coil currents, () → CoilCurrents
                                                                                      // signed (CUR_A, CUR_B) from MSCURACT
        println("tstep " + motor.readTstep())                                         // read step interval, () → long clocks
                                                                                      // time between 1/256 microsteps
        println("scale " + motor.readCurrentScale())                                  // read actual current scale, () → int 0–31
                                                                                      // DRV_STATUS.CS_ACTUAL
        println("sg_result " + motor.readStallguard())                                // read StallGuard2 result, () → int 0–1023
                                                                                      // SG_RESULT, 0 = highest load
        println("stalled " + motor.isStalled())                                       // read stall flag, () → boolean
                                                                                      // DRV_STATUS.StallGuard
        println("diag0 " + motor.readDiag(0))                                         // read DIAG0 pin, (n 0 or 1) → boolean
                                                                                      // needs the diag pin to be wired
        println("pwm_scale " + motor.readPwmScale())                                  // read PWM scaler, () → int 0–255
                                                                                      // PWM_SCALE
        println("lost_steps " + motor.readLostSteps())                                // read skipped DcStep steps, () → long
                                                                                      // LOST_STEPS
        println("status " + motor.readStatus())                                       // read driver status, () → Status
                                                                                      // decoded DRV_STATUS
        println("standstill " + motor.isStandstill())                                 // read standstill flag, () → boolean
                                                                                      // DRV_STATUS.stst
        println("fault " + motor.readFault())                                         // read fault bitmask, () → int
                                                                                      // FAULT_* constants, 0 = none
        println("gstat " + motor.readGlobalStatus())                                  // read GSTAT, () → GlobalStatus
                                                                                      // reset, drv_err, uv_cp; the chip clears them on read
        println("ioin " + motor.readIoin())                                           // read IOIN, () → Ioin
                                                                                      // raw word, pin states and silicon version
        println("gconf " + java.lang.Long.toHexString(motor.readRegister(0x00)))                // read a raw register, (reg) → long
                                                                                      // any readable register
        motor.writeRegister(0x13, 500L, true)                                         // write a raw register, (reg, value, verify) → void
                                                                                      // here TPWMTHRS again, with the mirror check
        motor.setDcstep(0L, 0, 0)                                                     // configure DcStep, (vdcmin, dcTime, dcSg) → void
                                                                                      // all zero keeps DcStep off
        motor.setDirectMode(true)                                                     // direct coil-current mode, (enabled) → void
                                                                                      // GCONF.direct_mode
        motor.setCoilCurrents(100, 0)                                                 // write XDIRECT, (coilA, coilB -255..255) → void
                                                                                      // scaled by IHOLD
        Thread.sleep(500)
        motor.setDirectMode(false)                                                    // back to STEP/DIR, (enabled) → void
                                                                                      // GCONF.direct_mode = 0
        motor.writeMicrostepTable(longArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0L, 0x00F70000L)// program the microstep table, (mslut 8 words, mslutsel, mslutstart) → void
                                                                                      // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0 (placeholder words, replace with a real table)
        motor.disable()                                                               // power stage off, () → void
                                                                                      // drives ENN high; the motor freewheels
    }
}
