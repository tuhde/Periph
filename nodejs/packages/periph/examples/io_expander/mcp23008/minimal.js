'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Mcp23008Minimal } = require('periph/src/chips/io_expander/mcp23008');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Mcp23008Minimal(connection);             // Create MCP23008 driver, (connection, addr=0x20)

    const p0 = chip.pin(0, 'in');                                   // Get pin proxy, (n, direction='in') → Pin
    const p7 = chip.pin(7, 'out');                                  // Get pin proxy, (n, direction='out') → Pin

    setInterval(async () => {
        const port = await chip.readPort();                         // Read all 8 pins, (port=0) → Promise<int bitmask>
        const level = await p0.read();                              // Read pin, () → Promise<int 0|1>
        await p7.write(level);                                      // Write pin, (value=0|1) → Promise<void>
        console.log('GPIO=0x' + port.toString(16).padStart(2, '0') + '  GP0=' + level);
    }, 200);
}

main();
