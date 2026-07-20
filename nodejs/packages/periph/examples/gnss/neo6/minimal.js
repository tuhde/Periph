'use strict';
const { UARTTransport } = require('../../../src/transport/uart');
const { NEO6Minimal } = require('../../../src/chips/gnss/neo6');

// To use I2C (DDC) instead of UART:
//   const { I2CTransport } = require('../../../src/transport/i2c');
//   const transport = new I2CTransport(1, 0x42);
//   const gps = new NEO6Minimal(transport, 'i2c');
// To use SPI instead of UART:
//   const { SPITransport } = require('../../../src/transport/spi');
//   const transport = new SPITransport(0, 0, { mode: 0, maxSpeedHz: 200_000 });
//   const gps = new NEO6Minimal(transport, 'spi');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';

async function main() {
    const transport = new UARTTransport(UART_PORT, { baudRate: 9600 });
    await transport.open();
    const gps = new NEO6Minimal(transport);            // Create NEO-6 driver, (transport, busType='uart')

    for (let i = 0; i < 20; i++) {
        if (await gps.update()) {                      // Read + parse one NMEA sentence, () → Promise<boolean>
            console.log(gps.latitude(), gps.longitude(), gps.altitude());
        }
        await new Promise(r => setTimeout(r, 50));
    }
    await transport.close();
}

main().catch(err => { console.error(err); process.exit(1); });
