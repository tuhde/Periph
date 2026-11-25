'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca6416aMinimal } = require('periph/src/chips/io_expander/pca6416a');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pca6416aMinimal(connection);             // Create PCA6416A driver, (connection, addr=0x20)

    const p0  = chip.pin(0, 'in');                                  // Get pin proxy P0_0, (n, direction='in') → Pin
    const p15 = chip.pin(15, 'out');                                // Get pin proxy P1_7, (n, direction='out') → Pin

    setInterval(async () => {
        const port = await chip.readPort(1);                        // Read the 8 pins of port 1, (port) → Promise<int bitmask>
        const level = await p0.read();                              // Read pin, () → Promise<int 0|1>
        await p15.write(level);                                     // Write pin, (value=0|1) → Promise<void>
        console.log('PORT1=0x' + port.toString(16).padStart(2, '0') + '  P0=' + level);
    }, 200);
}

main();
