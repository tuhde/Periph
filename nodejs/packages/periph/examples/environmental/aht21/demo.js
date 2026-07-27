'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AHT21Full } = require('../../../src/chips/environmental/aht21');

const connection = new I2CConnection(1, 0x38);
const aht = new AHT21Full(connection);                                  // Create AHT21 driver, (connection, addr=0x38) → void

// --- Verify calibration before starting the logging session ---
// Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
// the driver already sent the calibration init sequence during construction.
(async () => {
    console.log('Calibrated:', await aht.isCalibrated());              // Check calibration status, () → boolean
})();

console.log('Time     T (C)      RH (%)     Dew (C)');

let n = 0;
setInterval(async () => {
    // --- Each reading requires an 80 ms measurement cycle ---
    // The sensor cannot output data faster than this; the driver
    // handles the trigger + wait internally.
    const rc = await aht.readWithCrc();                                // Read with CRC verification, () → { temperature_c, humidity_pct, crc_ok }
    if (!rc.crc_ok) {
        console.log('CRC error at sample', n);
        return;
    }

    const t = rc.temperature_c;
    const rh = rc.humidity_pct;

    // --- Magnus formula dew-point approximation ---
    // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
    // dew_point = (243.04 * gamma) / (17.625 - gamma)
    // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
    const gamma = Math.log(rh / 100.0) + (17.625 * t) / (243.04 + t);
    const dew = (243.04 * gamma) / (17.625 - gamma);

    console.log(
        String(n).padEnd(9) +
        t.toFixed(2).padEnd(11) +
        rh.toFixed(2).padEnd(11) +
        dew.toFixed(2)
    );
    n++;
}, 5000);
