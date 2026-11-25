'use strict';

/**
 * Demo for the TMC2209 — sensorless homing of a linear axis with StallGuard4.
 *
 * Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
 * reading IOIN.VERSION (0x21) and IFCNT, then drives toward the end stop with
 * the internal step generator at 200 full steps/s. When SG_RESULT falls to
 * 2 * SGTHRS the axis has hit the stop: the demo stops, reverses for 2 s to
 * back off and finishes with a CoolStep move. Any fault stops the motor,
 * disables the driver and exits.
 */

const { UARTConnection } = require('periph/src/connection/uart');
const { TMC2209Full } = require('periph/src/chips/motor/tmc2209');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const MICROSTEPS = 16;
const HOMING_SPEED = 200;
const SGTHRS = 60;

(async () => {
    const port = process.env.UART_PORT || '/dev/ttyS0';
    const connection = new UARTConnection(port, { baudRate: 115200, timeoutMs: 100 });
    await connection.open();
    const motor = new TMC2209Full(connection);                                     // Create TMC2209 Full driver, (connection, {rSenseOhm=0.11 Ω, address=0, enPin})
    await motor.init();                                                            // Probe the chip and apply defaults, () → None

    async function guard() {
        // --- Stop on any driver fault ---
        // Short circuits and over-temperature latch until the driver is
        // disabled, so the demo shuts everything down and reports which fault fired.
        const fault = await motor.readFault();                                     // Read fault bitmask, () → number
        if (fault) {
            await motor.stop();                                                    // Stop the motor, () → None
            await motor.disable();                                                 // Power stage off, () → None
            console.log('fault 0x' + fault.toString(16));
            await connection.close();
            process.exit(1);
        }
    }

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    const ioin = await motor.readIoin();                                           // Read IOIN, () → object
    console.log('version 0x' + ioin.version.toString(16), 'IFCNT', await motor.readIfcnt());  // Read write counter, () → number
    await motor.setCurrent(0.8);                                                   // Set run current, (rmsA A RMS) → None
    await motor.setMicrosteps(MICROSTEPS);                                         // Set microstep resolution, (n 1–256) → None
    await motor.enable();                                                          // Power stage on, () → None

    // --- Arm StallGuard4 for the homing speed ---
    // StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
    // is set just below the TSTEP of the homing speed so detection is active.
    await motor.setStallThreshold(SGTHRS);                                         // Set StallGuard4 threshold, (sgthrs 0–255) → None
    await motor.setCoolstepThreshold(Math.floor(12e6 / (HOMING_SPEED * 256 / 2))); // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None

    // --- Drive toward the end stop ---
    // The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
    // the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
    await motor.run(HOMING_SPEED);                                                 // Spin with the internal generator, (stepsPerS full steps/s) → None
    await sleep(500);
    let sg;
    for (;;) {
        sg = await motor.readStallguard();                                         // Read StallGuard4 result, () → number 0–510
        console.log('SG_RESULT', sg);
        if (sg <= 2 * SGTHRS) break;
        await guard();
        await sleep(100);
    }

    // --- End stop found: stop and back off ---
    // Reversing for 2 s moves the axis clear of the stop.
    await motor.stop();                                                            // Stop the motor, () → None
    console.log('stalled at SG_RESULT', sg);
    await motor.run(-HOMING_SPEED);                                                // Spin with the internal generator, (stepsPerS full steps/s) → None
    await sleep(2000);
    await motor.stop();                                                            // Stop the motor, () → None

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    await motor.configureCoolstep(5, 2, 1, 1, 0);                                  // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
    await motor.run(HOMING_SPEED);                                                 // Spin with the internal generator, (stepsPerS full steps/s) → None
    for (let i = 0; i < 10; i++) {
        await sleep(500);
        console.log('CS_ACTUAL', await motor.readCurrentScale());                  // Read actual current scale, () → number 0–31
        await guard();
    }
    await motor.stop();                                                            // Stop the motor, () → None

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    console.log('temperature flags', await motor.readTemperatureFlags());          // Read temperature flags, () → number 0–15
    console.log('global status', await motor.readGlobalStatus());                  // Read GSTAT, (clear=false) → object
    await motor.disable();                                                         // Power stage off, () → None
    await connection.close();
})();
