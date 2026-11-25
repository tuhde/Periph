'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AHT10Full } = require('../../../src/chips/environmental/aht10');

const connection = new I2CConnection(1, 0x38);
const aht = new AHT10Full(connection);                                  // Create AHT10 driver, (connection, addr=0x38) → void

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

    const raw = await aht.readRaw();                                   // Read raw 20-bit values, () → { raw_humidity, raw_temperature }
                                                                       // same measurement as read(), undecoded
    console.log(raw.raw_humidity, raw.raw_temperature);

    console.log((await aht.readStatus()).toString(16));                // Read status byte, () → number
                                                                       // bit 7 BUSY, bits 6:5 MODE, bit 3 CAL
    console.log(await aht.dewPoint());                                 // Dew point, () → number °C
                                                                       // one fresh measurement, Magnus formula

    await aht.softReset();                                             // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms, re-runs the init command

    await connection.close();
})();
