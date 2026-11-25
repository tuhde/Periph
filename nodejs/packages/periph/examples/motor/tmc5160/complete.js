'use strict';

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC5160Full } = require('periph/src/chips/motor/tmc5160');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));                                    // Create DRV_ENN pin, (chip, line=17) → OutputPin
    const diag0Pin = { isHigh: () => false };                                      // Stand-in DIAG0 input; use a real InputPin when DIAG0 is wired
    const motor = new TMC5160Full(spi, { enPin, diag0Pin });                       // Create TMC5160 Full driver, (connection, {busType='spi', rSenseOhm=0.075 Ω, enPin, address=0, echo=true, multiNode=false, fclkHz=12000000 Hz, diag0Pin, diag1Pin})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None
                                                                                   // reads IOIN to verify the chip, writes the default configuration with verified writes

    await motor.setCurrent(1.5);                                                      // Set run current, (rmsA A RMS) → None
                                                                                      // sets GLOBALSCALER and IRUN; hold current becomes a third
    await motor.setHoldCurrent(0.3);                                                  // Set hold current, (rmsA A RMS) → None
                                                                                      // overrides the automatic one-third hold current
    await motor.setGlobalScaler(200);                                                 // Set raw GLOBALSCALER, (value 0 or 32–256) → None
                                                                                      // 32/256 … 256/256 of the maximum current
    await motor.setCurrentScale(31, 10, 6);                                           // Set raw IHOLD_IRUN, (irun, ihold, iholdDelay) → None
                                                                                      // direct register fields
    await motor.setPowerDownDelay(20);                                                // Set TPOWERDOWN, (tpowerdown 0–255) → None
                                                                                      // units of 2^18 clocks
    await motor.setSpeed(300);                                                        // Set target speed, (fullStepsPerS) → None
                                                                                      // VMAX
    await motor.setAcceleration(800);                                                 // Set acceleration, (fullStepsPerS2) → None
                                                                                      // A1 = AMAX = DMAX = D1
    await motor.configureRamp(0, 1200, 200, 600, 500, 900, 1500, 10);                 // Configure the SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
                                                                                      // physical units: full steps/s and full steps/s²
    await motor.setRampRaw(0, 1564, 0, 1564, 71583, 1564, 1564, 10);                  // Configure the ramp in raw units, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
                                                                                      // register values
    await motor.setZeroWait(0.1);                                                     // Set TZEROWAIT, (seconds) → None
                                                                                      // wait after ramp-down to zero
    await motor.setMicrosteps(256);                                                   // Set microstep resolution, (n 1–256) → None
                                                                                      // 256 is the normal setting with the motion controller
    await motor.setInterpolation(false);                                              // Interpolation, (enabled) → None
                                                                                      // only useful for external STEP/DIR
    await motor.setChopperMode(false);                                                // Choose chopper, (spreadcycle) → None
                                                                                      // false enables StealthChop below TPWMTHRS
    await motor.setStealthchopThreshold(500);                                         // Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                                                      // faster than this the chip switches to SpreadCycle
    await motor.setHighVelocityThreshold(0);                                          // Set THIGH, (thigh 20 bit) → None
                                                                                      // 0 keeps the high-velocity features off
    await motor.configureChopper(3, 2, 4, 1, 0);                                      // Configure SpreadCycle timing, (toff, tbl, hstrt, hend, tpfd) → None
                                                                                      // direct CHOPCONF fields with chm = 0
    await motor.configureConstantOffTime(3, 2, 4, 0, false, 0);                       // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, tpfd) → None
                                                                                      // chm = 1 with fast decay
    await motor.configureChopper(3, 2, 4, 1, 0);                                      // Back to SpreadCycle, (toff, tbl, hstrt, hend, tpfd) → None
                                                                                      // chm = 0 again
    await motor.configureHighVelocity(false, false);                                  // Behaviour above THIGH, (vhighchm, vhighfs) → None
                                                                                      // both off
    await motor.configureStealthchop(true, true, 30, 0, 0, 4, 12);                    // Configure StealthChop, (autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) → None
                                                                                      // direct PWMCONF fields
    await motor.setFreewheel(0);                                                      // Set standstill mode, (mode 0–3) → None
                                                                                      // PWMCONF.freewheel, only effective when IHOLD is 0
    await motor.setShortProtection(true, true);                                       // Short protections, (shortGnd, shortSupply) → None
                                                                                      // true keeps the protection enabled
    await motor.setDoubleEdge(false);                                                 // Step on both edges, (enabled) → None
                                                                                      // external STEP/DIR only
    await motor.setDirectionInverted(false);                                          // Invert direction, (inverted) → None
                                                                                      // GCONF.shaft
    await motor.setFastStandstill(false);                                             // Fast standstill timeout, (enabled) → None
                                                                                      // GCONF.faststandstill
    await motor.setRecalibrate(false);                                                // Zero-crossing recalibration, (enabled) → None
                                                                                      // GCONF.recalibrate
    await motor.setSmallHysteresis(false);                                            // Comparison hysteresis, (enabled) → None
                                                                                      // false = 1/16
    await motor.setEmergencyStop(false);                                              // ENCA_DCIN emergency stop, (enabled) → None
                                                                                      // GCONF.stop_enable
    await motor.configureDiag({});                                                    // DIAG pin function, ({diag0StepOutput, diag1DirOutput, diag0Pushpull, diag1Pushpull}) → None
                                                                                      // DIAG0 = interrupt, DIAG1 = position compare
    await motor.configureSwitches({ stopLeft: true, stopRight: true });               // Configure reference switches, ({stopLeft, stopRight, leftActiveLow, rightActiveLow, swap, softStop}) → None
                                                                                      // automatic stop on both switches
    await motor.configureLatch({ leftActive: true, rightActive: true });              // Configure position latching, ({leftActive, leftInactive, rightActive, rightInactive, latchEncoder}) → None
                                                                                      // latch XACTUAL on active-going switch edges
    await motor.setPositionCompare(100);                                              // Set X_COMPARE, (position full steps) → None
                                                                                      // DIAG1 high while XACTUAL = X_COMPARE
    await motor.setStallThreshold(0);                                                 // Set StallGuard2 threshold, (sgt -64..63) → None
                                                                                      // lower = more sensitive
    await motor.setStallguardFilter(false);                                           // StallGuard2 filter, (enabled) → None
                                                                                      // COOLCONF.sfilt
    await motor.setCoolstepThreshold(1000);                                           // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                                                      // active while TCOOLTHRS >= TSTEP >= THIGH
    await motor.configureCoolstep(5, 2, 1, 1, 0);                                     // Configure CoolStep, (semin, semax, seup, sedn, seimin) → None
                                                                                      // semin = 0 would disable CoolStep
    await motor.enableStallStop(false);                                               // Automatic stop on stall, (enabled) → None
                                                                                      // SW_MODE.sg_stop; needs TCOOLTHRS first
    await motor.setDcstep(0, 0, 0);                                                   // Configure DcStep, (vdcmin, dcTime, dcSg) → None
                                                                                      // all zero keeps DcStep off
    await motor.configureEncoder(false, false, false, false, false, false, false, false, false, false, false);// Configure the ABN encoder, (polA, polB, polN, ignoreAb, clrCont, clrOnce, posEdge, negEdge, clrEncX, latchXAct, decimal) → None
                                                                                      // ENCMODE bits
    await motor.setEncoderConstant(1.0);                                              // Set ENC_CONST, (value signed 16.16) → None
                                                                                      // encoder counts to microsteps
    await motor.setEncoderPosition(0);                                                // Set X_ENC, (counts) → None
                                                                                      // encoder position
    await motor.setEncoderDeviation(0);                                               // Set ENC_DEVIATION, (steps) → None
                                                                                      // 0 = off
    await motor.configureGateDriver(0, 4, 0, 0, 0);                                   // Configure DRV_CONF, (bbmtime, bbmclks, otselect, drvstrength, filtIsense) → None
                                                                                      // board-specific: wrong values can destroy the MOSFETs
    await motor.configureShortDetection(6, 6, 1, 0);                                  // Configure SHORT_CONF, (s2vsLevel, s2gLevel, shortfilter, shortdelay) → None
                                                                                      // board-specific
    await motor.enable();                                                             // Power stage on, () → None
                                                                                      // drives DRV_ENN low, waits 150 ms
    await motor.setPosition(0);                                                       // Define the position, (position full steps) → None
                                                                                      // writes XACTUAL and XTARGET
    await motor.moveTo(200);                                                          // Positioning move, (position full steps) → None
                                                                                      // non-blocking
    console.log('reached', await motor.waitUntilReached());                           // Wait for the target, (timeoutS) → boolean
                                                                                      // polls RAMP_STAT
    console.log('position', await motor.position());                                  // Read actual position, () → number full steps
                                                                                      // XACTUAL
    console.log('velocity', await motor.velocity());                                  // Read actual velocity, () → number full steps/s
                                                                                      // VACTUAL
    console.log('is_reached', await motor.isPositionReached());                       // Position reached flag, () → boolean
                                                                                      // RAMP_STAT.position_reached
    await motor.move(-200, 400);                                                      // Blocking relative move, (fullSteps, fullStepsPerS) → None
                                                                                      // optional speed override
    await motor.run(150);                                                             // Velocity mode, (fullStepsPerS) → None
                                                                                      // sign = direction
    await sleep(1000);
    await motor.setRampMode(1);                                                       // Set RAMPMODE, (mode 0–3) → None
                                                                                      // 1 = velocity mode to +VMAX
    await motor.hold();                                                               // Hold mode, () → None
                                                                                      // keep the present velocity
    await motor.stop();                                                               // Controlled stop, () → None
                                                                                      // VMAX = 0
    await motor.waitUntilReached();                                                   // Wait for standstill, (timeoutS) → boolean
                                                                                      // vzero
    await motor.setTargetRaw(51200);                                                  // Write XTARGET raw, (xtarget microsteps) → None
                                                                                      // starts positioning in mode 0
    await motor.setRampMode(0);                                                       // Back to positioning, (mode) → None
                                                                                      // RAMPMODE = 0
    console.log('ramp', await motor.readRampStatus());                                // Read RAMP_STAT, () → object
                                                                                      // decoded ramp and switch flags
    await motor.clearRampEvents();                                                    // Clear event flags, () → None
                                                                                      // write-1-to-clear bits
    console.log('switches', await motor.readSwitches());                              // Read switch states, () → [boolean, boolean]
                                                                                      // [left, right]
    console.log('latch', await motor.readLatchedPosition());                          // Read XLATCH, () → number full steps
                                                                                      // position at the last switch event
    console.log('tstep', await motor.readTstep());                                    // Read step interval, () → number clocks
                                                                                      // time between 1/256 microsteps
    console.log('mscnt', await motor.microstepPosition());                            // Read microstep counter, () → number 0–1023
                                                                                      // MSCNT
    console.log('currents', await motor.readCoilCurrents());                          // Read coil currents, () → [number, number]
                                                                                      // signed [CUR_A, CUR_B] from MSCURACT
    console.log('scale', await motor.readCurrentScale());                             // Read actual current scale, () → number 0–31
                                                                                      // DRV_STATUS.CS_ACTUAL
    console.log('sg_result', await motor.readStallguard());                           // Read StallGuard2 result, () → number 0–1023
                                                                                      // SG_RESULT, 0 = highest load
    console.log('stalled', await motor.isStalled());                                  // Read stall flag, () → boolean
                                                                                      // DRV_STATUS.StallGuard
    console.log('pwm_scale', await motor.readPwmScale());                             // Read PWM_SCALE, () → [number, number]
                                                                                      // [PWM_SCALE_SUM, PWM_SCALE_AUTO]
    console.log('pwm_auto', await motor.readPwmAuto());                               // Read PWM_AUTO, () → [number, number]
                                                                                      // [PWM_OFS_AUTO, PWM_GRAD_AUTO]
    console.log('offset', await motor.readOffset());                                  // Read OFFSET_READ, () → [number, number]
                                                                                      // offset calibration of both phases
    console.log('encoder', await motor.readEncoderPosition(), await motor.readEncoderLatch(), await motor.readEncoderStatus());// Read the encoder, () → number, number, object
                                                                                      // X_ENC, ENC_LATCH, ENC_STATUS
    await motor.clearEncoderStatus();                                                 // Clear encoder events, () → None
                                                                                      // write-1-to-clear
    console.log('status', await motor.readStatus());                                  // Read driver status, () → object
                                                                                      // decoded DRV_STATUS
    console.log('standstill', await motor.isStandstill());                            // Read standstill flag, () → boolean
                                                                                      // DRV_STATUS.stst
    console.log('fault', await motor.readFault());                                    // Read fault bitmask, () → number
                                                                                      // FAULT_* constants, 0 = none
    console.log('gstat', await motor.readGlobalStatus());                             // Read GSTAT, () → object
                                                                                      // reset, drv_err, uv_cp; not cleared by the read
    await motor.clearGlobalStatus();                                                  // Clear GSTAT, () → None
                                                                                      // writes 0x07
    console.log('ioin', await motor.readIoin());                                      // Read IOIN, () → object
                                                                                      // raw word, pin states and silicon version
    console.log('gconf', (await motor.readRegister(0x00)).toString(16));              // Read a raw register, (reg) → number
                                                                                      // any readable register
    await motor.writeRegister(0x13, 500, true);                                       // Write a raw register, (reg, value, verify) → None
                                                                                      // here TPWMTHRS again, with the mirror check
    await motor.setDirectMode(true);                                                  // Direct coil-current mode, (enabled) → None
                                                                                      // bypasses the ramp generator
    await motor.setCoilCurrents(100, 0);                                              // Write XDIRECT, (coilA, coilB -255..255) → None
                                                                                      // scaled by IHOLD
    await sleep(500);
    await motor.setDirectMode(false);                                                 // Back to the ramp generator, (enabled) → None
                                                                                      // restores XTARGET
    console.log('diag0', motor.readDiag(0));                                        // Read DIAG0 pin, (n 0 or 1) → boolean
                                                                                   // needs the diag pin to be wired (SPI only)
    const customTable = null;                                                      // replace with { mslut: [8 words], mslutsel, mslutstart } to program a table
    if (customTable && (await motor.microstepPosition()) === 0) {
        await motor.writeMicrostepTable(customTable.mslut, customTable.mslutsel, customTable.mslutstart);  // Program the microstep table, (mslut 8 words, mslutsel, mslutstart) → None
                                                                                   // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
    }
    for (const call of [() => motor.setSlaveAddress(0), () => motor.setSendDelay(2), () => motor.setNaoPolarity(true), () => motor.readIfcnt()]) {
        try { await call(); } catch (e) { console.log('UART only'); }              // setSlaveAddress, setSendDelay, setNaoPolarity and readIfcnt need busType 'uart'
    }
    await motor.disable();                                                         // Power stage off, () → None
                                                                                   // drives DRV_ENN high; the outputs float
    await spi.close();
})();
