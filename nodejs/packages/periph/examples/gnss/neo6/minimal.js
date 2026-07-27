'use strict';
const { UARTConnection } = require('../../../src/connection/uart');
const { NEO6Minimal } = require('../../../src/chips/gnss/neo6');

// To use I2C (DDC) instead of UART:
//   const { I2CConnection } = require('../../../src/connection/i2c');
//   const connection = new I2CConnection(1, 0x42);
//   const gps = new NEO6Minimal(connection, 'i2c');
// To use SPI instead of UART:
//   const { SPIConnection } = require('../../../src/connection/spi');
//   const connection = new SPIConnection(0, 0, { mode: 0, maxSpeedHz: 200_000 });
//   const gps = new NEO6Minimal(connection, 'spi');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';

async function main() {
    const connection = new UARTConnection(UART_PORT, { baudRate: 9600 });
    await connection.open();
    const gps = new NEO6Minimal(connection);            // Create NEO-6 driver, (connection, busType='uart')

    for (let i = 0; i < 20; i++) {
        if (await gps.update()) {                      // Read + parse one NMEA sentence, () → Promise<boolean>
            console.log(gps.latitude(), gps.longitude(), gps.altitude());
        }
        await new Promise(r => setTimeout(r, 50));
    }
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
