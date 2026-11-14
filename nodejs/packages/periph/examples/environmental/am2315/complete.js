'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AM2315Full } = require('../../../src/chips/environmental/am2315');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x5C) → Connection
const sensor = new AM2315Full(connection);                                // Create AM2315 driver, (connection) → void

async function main() {
    const r = await sensor.read();                                       // Read temperature and humidity, () → {temperature_c, humidity_pct}
                                                                         // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    console.log(r.temperature_c, r.humidity_pct);
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
    console.log(await sensor.readRaw());                                 // Read raw words, () → [number, number]
                                                                         // [humidity x10, temperature x10 as sign + magnitude]
    console.log(await sensor.readFresh());                               // Real-time value, () → {temperature_c, humidity_pct}
                                                                         // two reads >= 2 s apart; returns the second
    console.log(await sensor.readRegisters(0x08, 7));                    // Read registers, (start, count) → Buffer
                                                                         // function 0x03, 1-10 registers, CRC verified
    console.log(await sensor.readDeviceInfo());                          // Read device information, () → {model, version, device_id}
    console.log(await sensor.readUserRegisters());                       // Read user registers, () → [number, number]
    await sensor.writeUserRegisters(0x1234, 0xABCD);                     // Write user registers, (user1, user2) → void
    await sensor.writeRegisters(0x10, [0x00, 0x01]);                     // Write registers, (start, data) → void
                                                                         // only the user registers 0x10-0x13 are allowed
    await sensor.wake();                                                 // Wake the sensor, () → void
                                                                         // dummy write, the NACK is ignored
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
