'use strict';

/**
 * Demo for the TMC2208 — a silent desk-fan / camera-slider controller.
 *
 * Enables the driver at 0.8 A with 16 microsteps, proves the UART link by
 * reading IOIN.VERSION and IFCNT, then ramps the internal step generator
 * from 0 to 600 full steps/s in 100 steps/s increments (the chip has no ramp
 * of its own) printing StealthChop/SpreadCycle and CS_ACTUAL at each step.
 * TPWMTHRS switches to SpreadCycle above 400 steps/s. After holding speed
 * for 3 s it ramps down, reverses and repeats once. Any fault stops the
 * motor, disables the driver and exits.
 */

const { UARTConnection } = require('periph/src/connection/uart');
const { TMC2208Full } = require('periph/src/chips/motor/tmc2208');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const MICROSTEPS = 16;
const SPREAD_ABOVE = 400;

(async () => {
    const port = process.env.UART_PORT || '/dev/ttyS0';
    const connection = new UARTConnection(port, { baudRate: 115200, timeoutMs: 100 });
    await connection.open();
    const motor = new TMC2208Full(connection);                                     // Create TMC2208 Full driver, (connection, {rSenseOhm=0.11 Ω, enPin})
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

    async function ramp(from, to, sign) {
        // --- Ramp the internal generator in 100 steps/s increments ---
        // VACTUAL has no acceleration of its own, so the demo steps the speed
        // and reports whether the chip is in StealthChop or SpreadCycle.
        const inc = to >= from ? 100 : -100;
        for (let speed = from; inc > 0 ? speed <= to : speed >= to; speed += inc) {
            await motor.run(sign * speed);                                         // Spin with the internal generator, (stepsPerS full steps/s) → None
            await sleep(500);
            const status = await motor.readStatus();                               // Read driver status, () → object
            console.log(`${speed} steps/s ${status.stealth ? 'stealth' : 'spread'} CS=${status.cs_actual}`);
            await guard();
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

    // --- Quiet at low speed, strong at high speed ---
    // StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
    // change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
    await motor.setStealthchopThreshold(Math.floor(12e6 / (SPREAD_ABOVE * 256)));  // Set StealthChop velocity limit, (tpwmthrs 20 bit) → None

    for (const sign of [1, -1]) {
        await ramp(0, 600, sign);
        await sleep(3000);
        await ramp(600, 0, sign);
        await motor.stop();                                                        // Stop the motor, () → None
        await sleep(1000);
    }

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    console.log('temperature flags', await motor.readTemperatureFlags());          // Read temperature flags, () → number 0–15
    console.log('global status', await motor.readGlobalStatus());                  // Read GSTAT, (clear=false) → object
    await motor.disable();                                                         // Power stage off, () → None
    await connection.close();
})();
