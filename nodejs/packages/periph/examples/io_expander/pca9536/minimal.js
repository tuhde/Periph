'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca9536Minimal } = require('periph/src/chips/io_expander/pca9536');

async function main() {
    const connection = new I2CConnection(1, 0x41);                 // Create I2C connection, (bus, addr=0x41)
    const chip       = new Pca9536Minimal(connection);             // Create PCA9536 driver, (connection, addr=0x41)

    const p2 = chip.pin(2, 'in');                                   // Get pin proxy, (n, direction='in') → Pin
    const p1 = chip.pin(1, 'out');                                  // Get pin proxy, (n, direction='out') → Pin

    setInterval(async () => {
        const port = await chip.readPort();                         // Read all 4 pins, (port=0) → Promise<int bitmask>
        const level = await p2.read();                              // Read pin, () → Promise<int 0|1>
        await p1.write(level);                                      // Write pin, (value=0|1) → Promise<void>
        console.log('PORT=0x' + port.toString(16).padStart(1, '0') + '  P2=' + level);
    }, 200);
}

main();
