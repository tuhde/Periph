'use strict';

/**
 * Demo for the TMC2130 — sensorless homing of a linear axis with StallGuard2.
 *
 * Enables the driver at 0.8 A with 16 microsteps, proves the SPI link by
 * reading IOIN.VERSION (0x11), switches to SpreadCycle (StallGuard2 does not
 * work in StealthChop) and drives toward the end stop in bursts of 20 full
 * steps at 150 full steps/s. When SG_RESULT reaches 0 the demo stops, prints
 * the SG_RESULT history, backs off 200 full steps and finishes with a
 * CoolStep move. Any fault disables the driver and exits.
 */

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC2130Full } = require('periph/src/chips/motor/tmc2130');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const HOMING_SPEED = 150;
const BURST = 20;

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const stepPin = new GpioOutputPin(Default.output({ chip: 0, line: 27 }));                                  // Create STEP pin, (chip, line=27) → OutputPin
    const dirPin = new GpioOutputPin(Default.output({ chip: 0, line: 22 }));                                   // Create DIR pin, (chip, line=22) → OutputPin
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));                                    // Create ENN pin, (chip, line=17) → OutputPin
    const motor = new TMC2130Full(spi, stepPin, dirPin, { enPin });                // Create TMC2130 Full driver, (connection, stepPin, dirPin, {rSenseOhm=0.11 Ω, enPin})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    async function guard() {
        // --- Stop on any driver fault ---
        // Short circuits and over-temperature latch until the driver is
        // disabled, so the demo shuts everything down and reports which fault fired.
        const fault = await motor.readFault();                                     // Read fault bitmask, () → number
        if (fault) {
            await motor.disable();                                                 // Power stage off, () → None
            console.log('fault 0x' + fault.toString(16));
            await spi.close();
            process.exit(1);
        }
    }

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
    // motion is attempted.
    const ioin = await motor.readIoin();                                           // Read IOIN, () → object
    console.log('version 0x' + ioin.version.toString(16));
    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
    await motor.setMicrosteps(16);                                                 // Set microstep resolution, (n 1–256) → None
    await motor.enable();                                                          // Power stage on, () → None

    // --- Arm StallGuard2 for the homing speed ---
    // StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
    // just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
    await motor.setChopperMode(true);                                              // Choose chopper, (spreadcycle) → None
    await motor.setStallThreshold(0);                                              // Set StallGuard2 threshold, (sgt -64..63) → None
    await motor.setCoolstepThreshold(Math.floor(12e6 / (HOMING_SPEED * 256 / 2))); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None

    // --- Drive toward the end stop in short bursts ---
    // The first burst is spin-up, where SG_RESULT is not yet valid; after each
    // burst the demo reads SG_RESULT until the stall flag is set.
    const history = [];
    await motor.move(BURST, HOMING_SPEED);                                         // Move, (fullSteps, fullStepsPerS full steps/s) → None
    for (;;) {
        await motor.move(BURST, HOMING_SPEED);                                     // Move, (fullSteps, fullStepsPerS full steps/s) → None
        const sg = await motor.readStallguard();                                   // Read StallGuard2 result, () → number 0–1023
        history.push(sg);
        console.log('SG_RESULT', sg);
        if (await motor.isStalled()) break;                                        // Read stall flag, () → boolean
        await guard();
    }

    // --- End stop found: report and back off ---
    // The last few SG_RESULT values show how the load built up before the stall.
    console.log('history', history.slice(-5));
    await motor.move(-200, HOMING_SPEED);                                          // Move, (fullSteps, fullStepsPerS full steps/s) → None

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    await motor.configureCoolstep(5, 2, 1, 1, 0);                                  // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
    for (let i = 0; i < 10; i++) {
        await motor.move(20, HOMING_SPEED);                                        // Move, (fullSteps, fullStepsPerS full steps/s) → None
        console.log('CS_ACTUAL', await motor.readCurrentScale());                  // Read actual current scale, () → number 0–31
        await guard();
    }

    // --- Shut down and report ---
    // GSTAT shows whether the run stressed the driver; the chip clears it on read.
    console.log('status', await motor.readStatus());                               // Read driver status, () → object
    console.log('global status', await motor.readGlobalStatus());                  // Read GSTAT, () → object
    await motor.disable();                                                         // Power stage off, () → None
    await spi.close();
})();
