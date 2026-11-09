'use strict';

const { UARTConnection } = require('periph/src/connection/uart');
const { TMC2209Full } = require('periph/src/chips/motor/tmc2209');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const port = process.env.UART_PORT || '/dev/ttyS0';
    const connection = new UARTConnection(port, { baudRate: 115200, timeoutMs: 100 });
    await connection.open();
    const motor = new TMC2209Full(connection, { rSenseOhm: 0.11 });               // Create TMC2209 Full driver, (connection, {rSenseOhm=0.11 Ω, address=0, enPin, echo=true, stepPin, dirPin, diagPin, multiNode=false, fclkHz=12000000 Hz})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None
                                                                                   // reads IOIN to verify the chip, writes the default configuration and checks IFCNT

    await motor.enable();                                                          // Power stage on, () → None
                                                                                   // restores TOFF (or drives ENN low when enPin is wired)
    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
                                                                                   // converts amperes to the 0–31 scale; hold current becomes a third
    await motor.setHoldCurrent(0.2);                                               // Set hold current, (rmsA A RMS) → None
                                                                                   // overrides the automatic one-third hold current
    await motor.setCurrentDelay(6, 20);                                            // Set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → None
                                                                                   // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    await motor.setMicrosteps(32);                                                 // Set microstep resolution, (n 1–256) → None
                                                                                   // writes CHOPCONF.MRES
    await motor.setInterpolation(true);                                            // Interpolate to 256 microsteps, (enabled) → None
                                                                                   // sets CHOPCONF.intpol
    await motor.setChopperMode(false);                                             // Choose chopper, (spreadcycle) → None
                                                                                   // false selects StealthChop
    await motor.setStealthchopThreshold(300);                                      // Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                                                   // faster than this the chip switches to SpreadCycle
    await motor.configureChopper(3, 2, 5, 0);                                      // Configure chopper timing, (toff, tbl, hstrt, hend) → None
                                                                                   // direct CHOPCONF fields
    await motor.configureStealthchop(true, true, 36, 14, 1, 4, 12);                // Configure StealthChop, (autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) → None
                                                                                   // direct PWMCONF fields
    await motor.setFreewheel(0);                                                   // Set standstill mode, (mode 0–3) → None
                                                                                   // PWMCONF.freewheel, only effective when IHOLD is 0
    await motor.setVsense(false);                                                  // Select sense range, (highSensitivity) → None
                                                                                   // false keeps the 0.325 V full scale
    await motor.setAnalogScaling(true);                                            // Current reference source, (enabled) → None
                                                                                   // true takes the reference from VREF
    await motor.setDirectionInverted(false);                                       // Invert direction, (inverted) → None
                                                                                   // GCONF.shaft
    await motor.setIndexOutput(0);                                                 // Select INDEX output, (mode 0–2) → None
                                                                                   // 0 first microstep, 1 pre-warning, 2 step pulses
    await motor.setDriverProtection(true, true);                                   // Short protections, (shortLs, shortGnd) → None
                                                                                   // true keeps the protection enabled
    await motor.setDoubleEdge(false);                                              // Step on both edges, (enabled) → None
                                                                                   // CHOPCONF.dedge
    await motor.setStallThreshold(60);                                             // Set StallGuard4 threshold, (sgthrs 0–255) → None
                                                                                   // stall when SG_RESULT <= 2 * SGTHRS
    await motor.setCoolstepThreshold(1000);                                        // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                                                   // active while TCOOLTHRS >= TSTEP > TPWMTHRS
    await motor.configureCoolstep(5, 2, 1, 1, 0);                                  // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
                                                                                   // semin = 0 would disable CoolStep
    await motor.setSendDelay(0);                                                   // Set reply delay, (n 0–15) → None
                                                                                   // NODECONF.SENDDELAY

    await motor.run(300);                                                          // Spin with the internal generator, (stepsPerS full steps/s) → None
                                                                                   // converts to VACTUAL using the 12 MHz clock
    await sleep(2000);
    console.log('position', await motor.microstepPosition());                      // Read microstep counter, () → number 0–1023
                                                                                   // MSCNT
    console.log('currents', await motor.readCoilCurrents());                       // Read coil currents, () → [number, number] table values
                                                                                   // two signed 9-bit MSCURACT fields
    console.log('sg_result', await motor.readStallguard());                        // Read StallGuard4 result, () → number 0–510
                                                                                   // SG_RESULT, higher = less load
    try {
        console.log('stalled', motor.isStalled());                                 // Read DIAG pin, () → boolean
                                                                                   // needs a diagPin; true on a stall or a driver error
    } catch (e) {
        console.log('stalled: no diagPin wired');
    }
    console.log('tstep', await motor.readTstep());                                 // Read step interval, () → number clocks
                                                                                   // time between 1/256 microsteps
    console.log('scale', await motor.readCurrentScale());                          // Read actual current scale, () → number 0–31
                                                                                   // DRV_STATUS.CS_ACTUAL
    console.log('temp', await motor.readTemperatureFlags());                       // Read temperature flags, () → number 0–15
                                                                                   // bit 0 t120 … bit 3 t157
    console.log('status', await motor.readStatus());                               // Read driver status, () → object
                                                                                   // decoded DRV_STATUS
    console.log('standstill', await motor.isStandstill());                         // Read standstill flag, () → boolean
                                                                                   // DRV_STATUS.stst
    console.log('fault', await motor.readFault());                                 // Read fault bitmask, () → number
                                                                                   // FAULT_* constants, 0 = none
    await motor.stop();                                                            // Stop the motor, () → None
                                                                                   // VACTUAL = 0, STEP input takes over again

    console.log('gstat', await motor.readGlobalStatus(true));                      // Read and clear GSTAT, (clear=false) → object
                                                                                   // reset, drv_err, uv_cp
    console.log('ifcnt', await motor.readIfcnt());                                 // Read write counter, () → number
                                                                                   // IFCNT
    console.log('ioin', await motor.readIoin());                                   // Read IOIN, () → object
                                                                                   // raw word, decoded pins and silicon version
    console.log('otp', (await motor.readOtp()).toString(16));                      // Read OTP image, () → number
                                                                                   // OTP_READ
    console.log('pwm_auto', await motor.readPwmAuto());                            // Read StealthChop tuning, () → object
                                                                                   // PWM_SCALE and PWM_AUTO results
    console.log('gconf', (await motor.readRegister(0x00)).toString(16));           // Read a raw register, (reg) → number
                                                                                   // any readable register
    await motor.writeRegister(0x13, 300);                                          // Write a raw register, (reg, value) → None
                                                                                   // here TPWMTHRS again
    await motor.disable();                                                         // Power stage off, () → None
                                                                                   // the motor freewheels
    await connection.close();
})();
