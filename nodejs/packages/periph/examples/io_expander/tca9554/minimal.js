'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Tca9554Minimal } = require('periph/src/chips/io_expander/tca9554');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Tca9554Minimal(connection);             // Create TCA9554 driver, (connection, addr=0x20)

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
