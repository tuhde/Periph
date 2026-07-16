'use strict';
const { UARTTransport } = require('../../../src/transport/uart');
const { NEO6Full } = require('../../../src/chips/gnss/neo6');

// To use I2C (DDC) instead of UART:
//   const { I2CTransport } = require('../../../src/transport/i2c');
//   const transport = new I2CTransport(1, 0x42);
//   const gps = new NEO6Full(transport, 'i2c');
// To use SPI instead of UART:
//   const { SPITransport } = require('../../../src/transport/spi');
//   const transport = new SPITransport(0, 0, { mode: 0, maxSpeedHz: 200_000 });
//   const gps = new NEO6Full(transport, 'spi');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';

async function main() {
    const transport = new UARTTransport(UART_PORT, { baudRate: 9600 });
    await transport.open();
    const gps = new NEO6Full(transport);               // Create NEO-6 driver, (transport, busType='uart')

    await gps.setRate(1);                              // Set navigation update rate, (hz) → Promise<void>
                                                        // writes CFG-RATE with measRate = 1000/hz ms
    await gps.setPlatform(0);                          // Set dynamic platform model, (model 0-8) → Promise<void>
                                                        // writes CFG-NAV5 with mask=dynModel only
    await gps.saveConfig();                            // Persist current configuration, () → Promise<void>
                                                        // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

    for (let i = 0; i < 20; i++) {
        if (await gps.update()) {                      // Read + parse one NMEA sentence, () → Promise<boolean>
            console.log(gps.latitude(), gps.longitude(), gps.altitude());
                                                        // decimal degrees / decimal degrees / meters MSL
            console.log(gps.speed(), gps.course());     // Speed over ground, () → number|null m/s
                                                        // Course over ground, () → number|null deg
            console.log(gps.utcTime(), gps.utcDate());   // UTC time of last fix sentence, () → string|null hhmmss.ss
                                                        // UTC date of last RMC sentence, () → string|null ddmmyy
            console.log(gps.hdop());                    // Horizontal dilution of precision, () → number|null
        }
        await new Promise(r => setTimeout(r, 50));
    }

    const navStatus = await gps.pollUbx(0x01, 0x03);   // Poll a UBX message and return its payload, (msgClass, msgId) → Promise<Buffer>
    console.log('NAV-STATUS payload:', navStatus);

    await gps.coldStart();                             // Force a cold start via CFG-RST, () → Promise<void>

    await transport.close();
}

main().catch(err => { console.error(err); process.exit(1); });
