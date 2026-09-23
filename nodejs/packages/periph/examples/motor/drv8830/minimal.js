'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV8830Minimal } = require('periph/src/chips/motor/drv8830');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV8830Minimal.I2C_ADDRESS);
    const motor = new DRV8830Minimal(connection);                                  // Create DRV8830 driver, (connection)
    await motor.init();                                                            // Confirm device presence, () → None

    for (let i = 0; i < 5; i++) {
        await motor.drive(3.0);                                                    // Drive at regulated voltage, (voltage V, + = forward) → None
        await sleep(2000);
        await motor.drive(-3.0);                                                   // Drive at regulated voltage, (voltage V, - = reverse) → None
        await sleep(2000);
    }

    await motor.stop();                                                            // Coast to standby, () → None
    await connection.close();
})();
