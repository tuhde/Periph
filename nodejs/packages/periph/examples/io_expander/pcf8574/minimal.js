'use strict';
const { I2CConnection }   = require('periph/src/connection/i2c');
const { Pcf8574Minimal }  = require('periph/src/chips/io_expander/pcf8574');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pcf8574Minimal(connection);              // Create PCF8574 driver, (connection, addr=0x20)

    const p0 = chip.pin(0, 'out');                                  // Get pin proxy, (n, direction='out') → Pin
    const p4 = chip.pin(4, 'in');                                   // Get pin proxy, (n, direction='in') → Pin

    setInterval(async () => {
        const port = await chip.readPort();                        // Read all 8 pins, (port=0) → Promise<int bitmask>
        const btn  = (port >> 4) & 1;
        await p0.write(btn ? 1 : 0);                                // Write pin, (value=0|1) → Promise<void>
        console.log('port=0x' + port.toString(16).padStart(2, '0'));
    }, 200);
}

main();
