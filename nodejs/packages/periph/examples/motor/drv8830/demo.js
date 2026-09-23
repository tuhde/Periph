'use strict';

// Battery-powered toy motor controller: holds a regulated 3.0 V forward, then
// 2.0 V reverse, printing the commanded output every second — the DRV8830
// keeps that average voltage constant as the battery sags. Brakes, then
// coasts. After every drive() call the fault register is checked; a fault
// (e.g. a stalled motor tripping ILIMIT) stops the motor and clears it.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV8830Full } = require('periph/src/chips/motor/drv8830');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV8830Full.I2C_ADDRESS);
    const motor = new DRV8830Full(connection);                                     // Create DRV8830 Full driver, (connection)
    await motor.init();                                                            // Confirm device presence, () → None

    async function checkFault() {
        // --- Recover from a fault instead of leaving the bridge latched off ---
        // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
        // so the motor does not lurch back to the old command on clear.
        const f = await motor.readFault();                                         // Read fault status, () → {fault, ocp, uvlo, ots, ilimit}
        if (f.fault) {
            const names = ['ocp', 'uvlo', 'ots', 'ilimit'].filter((k) => f[k]).map((k) => k.toUpperCase());
            console.log('fault:', names.join(', '));
            await motor.stop();                                                    // Coast to standby, () → None
            await motor.clearFault();                                              // Clear fault bits, () → None
        }
    }

    async function run(voltage, seconds) {
        // --- Hold a regulated voltage and watch it stay put ---
        // The chip PWM-regulates the bridge against VCC internally, so the
        // commanded voltage (and motor speed) holds while the battery discharges.
        await motor.drive(voltage);                                                // Drive at regulated voltage, (voltage V, signed) → None
        await checkFault();
        for (let i = 0; i < seconds; i++) {
            await sleep(1000);
            const out = await motor.readOutput();                                  // Read back CONTROL, () → {voltage V, direction}
            console.log(`${out.direction.padEnd(7)} ${out.voltage.toFixed(2)} V`);
        }
    }

    await run(3.0, 5);
    await run(-2.0, 5);

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    await motor.brake();                                                           // Short-brake, () → None
    await sleep(500);
    await motor.stop();                                                            // Coast to standby, () → None

    await connection.close();
})();
