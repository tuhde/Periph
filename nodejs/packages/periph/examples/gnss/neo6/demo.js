'use strict';
const { UARTConnection } = require('../../../src/connection/uart');
const { NEO6Full } = require('../../../src/chips/gnss/neo6');

// To use I2C (DDC) instead of UART:
//   const { I2CConnection } = require('../../../src/connection/i2c');
//   const connection = new I2CConnection(1, 0x42);
//   const gps = new NEO6Full(connection, 'i2c');
// To use SPI instead of UART:
//   const { SPIConnection } = require('../../../src/connection/spi');
//   const connection = new SPIConnection(0, 0, { mode: 0, maxSpeedHz: 200_000 });
//   const gps = new NEO6Full(connection, 'spi');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';

// --- Portable GPS logger ---
// The module self-configures at factory defaults (9600 baud NMEA, 1 Hz); no
// CFG messages are needed for a basic position log. Runs for 60 seconds,
// polling update() far faster than the 1 Hz sentence rate so no sentence is
// missed, and prints one line per second once a fresh GGA has been parsed.
async function main() {
    const connection = new UARTConnection(UART_PORT, { baudRate: 9600 });
    await connection.open();
    const gps = new NEO6Full(connection);               // Create NEO-6 driver, (connection, busType='uart')

    const start = Date.now();
    while (Date.now() - start < 60_000) {
        const gotFix = await gps.update();             // Read + parse one NMEA sentence, () → Promise<boolean>

        // --- No fix yet: show the wait state ---
        // gpsFix alone would not be trustworthy here; update() already only
        // resolves true once the GGA fix-status field confirms a real fix,
        // so a plain fix() === 0 check is enough to detect the waiting state.
        if (gps.fix() === 0) {
            console.log(`waiting for fix... satellites in use: ${gps.satellites()}`);

        // --- Fix acquired: log the full position record ---
        // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips true
        // the position, altitude, and HDOP fields below are all populated
        // together.
        } else if (gotFix) {
            console.log(
                `${gps.utcTime()}  lat=${gps.latitude().toFixed(6)}  lon=${gps.longitude().toFixed(6)}  ` +
                `alt=${gps.altitude().toFixed(1)} m  sats=${gps.satellites()}  hdop=${gps.hdop()}`
            );
        }

        await new Promise(r => setTimeout(r, 200));
    }

    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
