'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AHT21Full } = require('../../../src/chips/environmental/aht21');

const connection = new I2CConnection(1, 0x38);
const aht = new AHT21Full(connection);                                  // Create AHT21 driver, (connection, addr=0x38) → void

(async () => {
    console.log(await aht.isCalibrated());                             // Check calibration status, () → boolean
                                                                       // reads CAL bit from status byte
    console.log(await aht.isBusy());                                   // Check busy status, () → boolean
                                                                       // reads BUSY bit from status byte

    const r = await aht.read();                                        // Trigger measurement, () → { temperature_c, humidity_pct }
                                                                       // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    console.log(r.temperature_c, r.humidity_pct);

    console.log(await aht.readTemperature());                          // Read temperature only, () → number °C
                                                                       // triggers full measurement, returns temperature_c
    console.log(await aht.readHumidity());                             // Read humidity only, () → number %RH
                                                                       // triggers full measurement, returns humidity_pct

    const rc = await aht.readWithCrc();                                // Read with CRC verification, () → { temperature_c, humidity_pct, crc_ok }
                                                                       // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    console.log(rc.temperature_c, rc.humidity_pct, rc.crc_ok);

    await aht.softReset();                                             // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms for recovery

    await connection.close();
})();
