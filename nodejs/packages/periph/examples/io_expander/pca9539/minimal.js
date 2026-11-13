'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca9539Minimal } = require('periph/src/chips/io_expander/pca9539');

async function main() {
    const connection = new I2CConnection(1, 0x74);                 // Create I2C connection, (bus, addr=0x74)
    const chip       = new Pca9539Minimal(connection);             // Create PCA9539 driver, (connection, addr=0x74)

    const p0  = chip.pin(0, 'in');                                  // Get pin proxy P00, (n, direction='in') → Pin
    const p15 = chip.pin(15, 'out');                                // Get pin proxy P17, (n, direction='out') → Pin

    setInterval(async () => {
        const port = await chip.readPort(1);                        // Read the 8 pins of port 1, (port) → Promise<int bitmask>
        const level = await p0.read();                              // Read pin, () → Promise<int 0|1>
        await p15.write(level);                                     // Write pin, (value=0|1) → Promise<void>
        console.log('PORT1=0x' + port.toString(16).padStart(2, '0') + '  P0=' + level);
    }, 200);
}

main();
