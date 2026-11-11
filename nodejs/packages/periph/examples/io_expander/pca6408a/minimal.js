'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca6408aMinimal } = require('periph/src/chips/io_expander/pca6408a');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pca6408aMinimal(connection);             // Create PCA6408A driver, (connection, addr=0x20)

    const p0 = chip.pin(0, 'in');                                   // Get pin proxy, (n, direction='in') → Pin
    const p7 = chip.pin(7, 'out');                                  // Get pin proxy, (n, direction='out') → Pin

    setInterval(async () => {
        const port = await chip.readPort();                         // Read all 8 pins, (port=0) → Promise<int bitmask>
        const level = await p0.read();                              // Read pin, () → Promise<int 0|1>
        await p7.write(level);                                      // Write pin, (value=0|1) → Promise<void>
        console.log('PORT=0x' + port.toString(16).padStart(2, '0') + '  P0=' + level);
    }, 200);
}

main();
