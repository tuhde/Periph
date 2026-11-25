'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AHT20Full } = require('../../../src/chips/environmental/aht20');

const connection = new I2CConnection(1, 0x38);
const aht = new AHT20Full(connection);                                  // Create AHT20 driver, (connection, addr=0x38) → void

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

(async () => {
    // --- Verify calibration before starting the logging session ---
    // The driver already checked the status word against 0x18 and, if needed,
    // soft-reset and wrote the calibration registers, so this should print true.
    console.log('Calibrated:', await aht.isCalibrated());              // Check calibration status, () → boolean

    let tMin = Infinity, tMax = -Infinity, rhMin = Infinity, rhMax = -Infinity;
    console.log('Sample   T (C)      RH (%)     Dew (C)');
    for (let n = 0; n < 12; n++) {
        // --- One measurement takes > 80 ms ---
        // The driver waits 80 ms after the trigger and polls BUSY, so the
        // calls below simply block until the data is valid.
        const r = await aht.read();                                    // Trigger measurement, () → { temperature_c, humidity_pct }
        const dew = await aht.dewPoint();                              // Dew point, () → number °C
        tMin = Math.min(tMin, r.temperature_c);  tMax = Math.max(tMax, r.temperature_c);
        rhMin = Math.min(rhMin, r.humidity_pct); rhMax = Math.max(rhMax, r.humidity_pct);
        console.log(
            String(n).padEnd(9) +
            r.temperature_c.toFixed(2).padEnd(11) +
            r.humidity_pct.toFixed(2).padEnd(11) +
            dew.toFixed(2)
        );
        // --- Every 10th sample, verify the transfer with the CRC-8 ---
        // The 7th byte protects bytes 0-5; a bad CRC means the I²C line is noisy.
        if (n % 10 === 9) {
            const c = await aht.readWithCrc();                         // Read with CRC check, () → { temperature_c, humidity_pct, crc_ok }
            console.log('CRC ok:', c.crc_ok);
        }
        // --- Keep the interval >= 1 s ---
        // Measuring more often self-heats the sensor and biases the reading.
        await sleep(5000);
    }
    console.log(`T min/max: ${tMin.toFixed(2)} / ${tMax.toFixed(2)} C   RH min/max: ${rhMin.toFixed(2)} / ${rhMax.toFixed(2)} %`);

    await aht.softReset();                                             // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms, re-runs the calibration check
    console.log('Calibrated after reset:', await aht.isCalibrated());  // Check calibration status, () → boolean
    await connection.close();
})();
