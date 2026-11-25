'use strict';

/**
 * Demo for the TMC5160 — sensorless homing and positioning of a linear axis.
 *
 * Enables the driver at 1.5 A, proves the link via IOIN.VERSION (0x30) and
 * SD_MODE, switches to SpreadCycle (StallGuard2 does not work in StealthChop),
 * arms the automatic stall stop and runs toward the end stop at 150 full
 * steps/s. The motion controller stops the motor itself on the stall; the demo
 * then defines zero, backs off, programs a two-phase ramp, makes three timed
 * positioning moves and enables CoolStep for the last one. Any fault stops the
 * motor, disables the driver and exits.
 */

const { SPIConnection } = require('periph/src/connection/spi');
const { GpioOutputPin } = require('periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC5160Full } = require('periph/src/chips/motor/tmc5160');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const HOMING_SPEED = 150;

(async () => {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });                       // Create SPI connection, (busNumber, deviceNumber, {mode=3, maxSpeedHz=1 MHz})
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));                                    // Create DRV_ENN pin, (chip, line=17) → OutputPin
    const motor = new TMC5160Full(spi, { enPin });                                  // Create TMC5160 Full driver, (connection, {busType='spi', rSenseOhm=0.075 Ω, enPin})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    async function guard() {
        // --- Stop on any driver fault ---
        // Short circuits and over-temperature latch until the driver is
        // disabled, so the demo shuts everything down and reports which fault fired.
        const fault = await motor.readFault();                                     // Read fault bitmask, () → number
        if (fault) {
            await motor.stop();                                                    // Controlled stop, () → None
            await motor.disable();                                                 // Power stage off, () → None
            console.log('fault 0x' + fault.toString(16));
            await spi.close();
            process.exit(1);
        }
    }

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and SD_MODE prove the wiring and the strap setting before
    // any motion is attempted.
    const ioin = await motor.readIoin();                                           // Read IOIN, () → object
    console.log('version 0x' + ioin.version.toString(16), 'SD_MODE', ioin.sd_mode);
    await motor.setCurrent(1.5);                                                   // Set run current, (rmsA A RMS) → None
    await motor.enable();                                                          // Power stage on, () → None

    // --- Arm the automatic stall stop ---
    // StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS; TCOOLTHRS is set
    // just above the TSTEP of the homing speed. sg_stop makes the chip stop by itself.
    await motor.setChopperMode(true);                                              // Choose chopper, (spreadcycle) → None
    await motor.setStallThreshold(0);                                              // Set StallGuard2 threshold, (sgt -64..63) → None
    await motor.setCoolstepThreshold(Math.floor(12e6 / (HOMING_SPEED * 256 / 2))); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
    await motor.setAcceleration(600);                                              // Set acceleration, (fullStepsPerS2) → None

    // --- Run toward the end stop ---
    // The first 0.5 s is spin-up, where stall detection is not yet valid; then
    // sg_stop is enabled and RAMP_STAT.event_stop_sg tells when the chip stopped.
    await motor.run(-HOMING_SPEED);                                                // Velocity mode, (fullStepsPerS) → None
    await sleep(500);
    await motor.enableStallStop(true);                                             // Automatic stop on stall, (enabled) → None
    while (!(await motor.readRampStatus()).event_stop_sg) {                        // Read RAMP_STAT, () → object
        console.log('SG_RESULT', await motor.readStallguard());                    // Read StallGuard2 result, () → number 0–1023
        await guard();
        await sleep(100);
    }

    // --- End stop found: define zero and back off ---
    // The motion controller has already stopped the motor; clear the event,
    // release sg_stop and move 20 full steps away while printing the ramp.
    await motor.clearRampEvents();                                                 // Clear event flags, () → None
    await motor.enableStallStop(false);                                            // Automatic stop on stall, (enabled) → None
    await motor.setPosition(0);                                                    // Define the position, (position full steps) → None
    await motor.setSpeed(300);                                                     // Set target speed, (fullStepsPerS) → None
    await motor.moveTo(20);                                                        // Positioning move, (position full steps) → None
    while (!(await motor.isPositionReached())) {                                   // Position reached flag, () → boolean
        console.log(await motor.position(), await motor.velocity(), await motor.readStallguard());  // position, velocity, SG_RESULT
        await sleep(50);
    }

    // --- Two-phase ramp, three timed moves ---
    // A1/D1 apply below V1 = 200 full steps/s, AMAX/DMAX above it.
    await motor.configureRamp(0, 1200, 200, 600, 500, 900, 1500, 10);             // Configure the SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
    await motor.setChopperMode(false);                                             // Choose chopper, (spreadcycle) → None
    for (let i = 0; i < 3; i++) {
        if (i === 2) await motor.configureCoolstep(5, 2, 1, 1, 0);                 // Configure CoolStep, (semin, semax, seup, sedn, seimin) → None
        await motor.moveTo(i % 2 === 0 ? 420 : 20);                                // Positioning move, (position full steps) → None
        await motor.waitUntilReached();                                            // Wait for the target, (timeoutS) → boolean
        console.log('move', i, 'done, CS_ACTUAL', await motor.readCurrentScale()); // Read actual current scale, () → number 0–31
        await guard();
    }

    // --- Shut down and report ---
    // GSTAT shows whether the run stressed the driver.
    console.log('status', await motor.readStatus());                               // Read driver status, () → object
    console.log('global status', await motor.readGlobalStatus());                  // Read GSTAT, () → object
    await motor.disable();                                                         // Power stage off, () → None
    await spi.close();
})();
