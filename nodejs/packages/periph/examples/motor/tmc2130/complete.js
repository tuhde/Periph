'use strict';

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC2130Full } = require('periph/src/chips/motor/tmc2130');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const stepPin = new GpioOutputPin(Default.output({ chip: 0, line: 27 }));                                  // Create STEP pin, (chip, line=27) → OutputPin
    const dirPin = new GpioOutputPin(Default.output({ chip: 0, line: 22 }));                                   // Create DIR pin, (chip, line=22) → OutputPin
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));                                    // Create ENN pin, (chip, line=17) → OutputPin
    const motor = new TMC2130Full(spi, stepPin, dirPin, { rSenseOhm: 0.11, enPin });   // Create TMC2130 Full driver, (connection, stepPin, dirPin, {rSenseOhm=0.11 Ω, enPin, diag0Pin, diag1Pin, fclkHz=12000000 Hz})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None
                                                                                   // reads IOIN to verify the chip, writes the default configuration with mirror checks

    await motor.enable();                                                          // Power stage on, () → None
                                                                                   // drives ENN low, waits 100 ms
    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
                                                                                   // converts amperes to the 0–31 scale, picks vsense automatically
    await motor.setHoldCurrent(0.2);                                               // Set hold current, (rmsA A RMS) → None
                                                                                   // overrides the automatic one-third hold current
    await motor.setCurrentDelay(6, 10);                                            // Set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → None
                                                                                   // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    await motor.setMicrosteps(32);                                                 // Set microstep resolution, (n 1–256) → None
                                                                                   // writes CHOPCONF.MRES
    await motor.setInterpolation(true);                                            // Interpolate to 256 microsteps, (enabled) → None
                                                                                   // sets CHOPCONF.intpol
    await motor.setChopperMode(false);                                             // Choose chopper, (spreadcycle) → None
                                                                                   // false enables StealthChop below TPWMTHRS
    await motor.setStealthchopThreshold(500);                                      // Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                                                   // faster than this the chip switches to SpreadCycle
    await motor.setHighVelocityThreshold(0);                                       // Set THIGH, (thigh 20 bit) → None
                                                                                   // 0 keeps the high-velocity features off
    await motor.configureChopper(3, 2, 4, 1);                                      // Configure SpreadCycle timing, (toff, tbl, hstrt, hend) → None
                                                                                   // direct CHOPCONF fields with chm = 0
    await motor.configureConstantOffTime(3, 2, 4, 0, false, false);                // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → None
                                                                                   // chm = 1 with fast decay
    await motor.configureChopper(3, 2, 4, 1);                                      // Back to SpreadCycle, (toff, tbl, hstrt, hend) → None
                                                                                   // chm = 0 again
    await motor.configureHighVelocity(false, false);                               // Behaviour above THIGH, (vhighchm, vhighfs) → None
                                                                                   // both off
    await motor.setChopperSync(0);                                                 // Set chopSync2, (sync 0–15) → None
                                                                                   // 0 = off
    await motor.configureStealthchop(true, false, 200, 1, 0);                      // Configure StealthChop, (autoscale, symmetric, pwmAmpl, pwmGrad, pwmFreq) → None
                                                                                   // direct PWMCONF fields
    await motor.setFreewheel(0);                                                   // Set standstill mode, (mode 0–3) → None
                                                                                   // PWMCONF.freewheel, only effective when IHOLD is 0
    await motor.setVsense(false);                                                  // Select sense range, (highSensitivity) → None
                                                                                   // false keeps the 0.325 V full scale; disables automatic selection
    await motor.setInternalRsense(false);                                          // Internal sense resistors, (enabled) → None
                                                                                   // keep false on boards with external resistors
    await motor.setAnalogScaling(false);                                           // Current reference source, (enabled) → None
                                                                                   // false uses the internal reference
    await motor.setDirectionInverted(false);                                       // Invert direction, (inverted) → None
                                                                                   // GCONF.shaft
    await motor.configureDiag({ diag0Stall: true, diag1Index: true });             // Route events to DIAG pins, ({diag0Error, diag0Otpw, diag0Stall, diag1Stall, diag1Index, diag1Onstate, diag0Pushpull, diag1Pushpull}) → None
                                                                                   // GCONF bits 5–13
    await motor.setDoubleEdge(false);                                              // Step on both edges, (enabled) → None
                                                                                   // CHOPCONF.dedge
    await motor.setShortProtection(true);                                          // Short-to-GND protection, (shortGnd) → None
                                                                                   // true keeps the protection enabled
    await motor.setSmallHysteresis(false);                                         // Comparison hysteresis, (enabled) → None
                                                                                   // false = 1/16
    await motor.setEmergencyStop(false);                                           // DCIN emergency stop, (enabled) → None
                                                                                   // GCONF.stop_enable
    await motor.setStallThreshold(0);                                              // Set StallGuard2 threshold, (sgt -64..63) → None
                                                                                   // lower = more sensitive
    await motor.setStallguardFilter(false);                                        // StallGuard2 filter, (enabled) → None
                                                                                   // COOLCONF.sfilt
    await motor.setCoolstepThreshold(1000);                                        // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                                                   // active while TCOOLTHRS >= TSTEP >= THIGH
    await motor.configureCoolstep(5, 2, 1, 1, 0);                                  // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
                                                                                   // semin = 0 would disable CoolStep

    await motor.move(200, 150);                                                    // Move, (fullSteps, fullStepsPerS full steps/s) → None
                                                                                   // blocking, software-timed STEP pulses
    await motor.step(400, 0.001);                                                  // Pulse the STEP pin, (count, delayS s) → None
                                                                                   // negative counts reverse via DIR
    console.log('position', await motor.microstepPosition());                      // Read microstep counter, () → number 0–1023
                                                                                   // MSCNT
    console.log('currents', await motor.readCoilCurrents());                       // Read coil currents, () → [number, number]
                                                                                   // signed [CUR_A, CUR_B] from MSCURACT
    console.log('tstep', await motor.readTstep());                                 // Read step interval, () → number clocks
                                                                                   // time between 1/256 microsteps
    console.log('scale', await motor.readCurrentScale());                          // Read actual current scale, () → number 0–31
                                                                                   // DRV_STATUS.CS_ACTUAL
    console.log('sg_result', await motor.readStallguard());                        // Read StallGuard2 result, () → number 0–1023
                                                                                   // SG_RESULT, 0 = highest load
    console.log('stalled', await motor.isStalled());                               // Read stall flag, () → boolean
                                                                                   // DRV_STATUS.StallGuard
    try {
        console.log('diag0', motor.readDiag(0));                                   // Read DIAG0 pin, (n 0 or 1) → boolean
                                                                                   // needs the diag pin to be wired
    } catch (e) {
        console.log('diag0: not wired');
    }
    console.log('pwm_scale', await motor.readPwmScale());                          // Read PWM scaler, () → number 0–255
                                                                                   // PWM_SCALE
    console.log('lost_steps', await motor.readLostSteps());                        // Read skipped DcStep steps, () → number
                                                                                   // LOST_STEPS
    console.log('status', await motor.readStatus());                               // Read driver status, () → object
                                                                                   // decoded DRV_STATUS
    console.log('standstill', await motor.isStandstill());                         // Read standstill flag, () → boolean
                                                                                   // DRV_STATUS.stst
    console.log('fault', await motor.readFault());                                 // Read fault bitmask, () → number
                                                                                   // FAULT_* constants, 0 = none
    console.log('gstat', await motor.readGlobalStatus());                          // Read GSTAT, () → object
                                                                                   // reset, drv_err, uv_cp; the chip clears them on read
    console.log('ioin', await motor.readIoin());                                   // Read IOIN, () → object
                                                                                   // raw word, pin states and silicon version
    console.log('gconf', (await motor.readRegister(0x00)).toString(16));           // Read a raw register, (reg) → number
                                                                                   // any readable register
    await motor.writeRegister(0x13, 500, true);                                    // Write a raw register, (reg, value, verify=false) → None
                                                                                   // here TPWMTHRS again, with the mirror check

    await motor.setDcstep(0, 0, 0);                                                // Configure DcStep, (vdcmin, dcTime, dcSg) → None
                                                                                   // all zero keeps DcStep off
    await motor.setDirectMode(true);                                               // Direct coil-current mode, (enabled) → None
                                                                                   // GCONF.direct_mode
    await motor.setCoilCurrents(100, 0);                                           // Write XDIRECT, (coilA, coilB -255..255) → None
                                                                                   // scaled by IHOLD
    await sleep(500);
    await motor.setDirectMode(false);                                              // Back to STEP/DIR, (enabled) → None
                                                                                   // GCONF.direct_mode = 0
    const customTable = null;                                                      // replace with { mslut: [8 words], mslutsel, mslutstart } to program a table
    if (customTable && (await motor.microstepPosition()) === 0) {
        await motor.writeMicrostepTable(customTable.mslut, customTable.mslutsel, customTable.mslutstart);  // Program the microstep table, (mslut 8 words, mslutsel, mslutstart) → None
                                                                                   // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
    }
    await motor.disable();                                                         // Power stage off, () → None
                                                                                   // drives ENN high; the motor freewheels
    await spi.close();
})();
