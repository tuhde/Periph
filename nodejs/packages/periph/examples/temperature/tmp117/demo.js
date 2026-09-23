'use strict';

// PT100-replacement cold-chain container thermometer: maximum averaging gives
// the lowest-noise reading, and the ALERT output fires when the cargo leaves
// the -25 °C to 8 °C safe transport range; the callback reports which
// boundary tripped. Set REFERENCE_C to a reference thermometer reading to
// calibrate once and persist the offset to EEPROM. After a fixed number of
// alerts the monitor unsubscribes.

const { I2CConnection } = require('periph/src/connection/i2c');
const { TMP117Full } = require('periph/src/chips/temperature/tmp117');

const MAX_ALERTS = 10;
const REFERENCE_C = process.env.REFERENCE_C ? parseFloat(process.env.REFERENCE_C) : null;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TMP117Full.I2C_ADDRESS);
    const sensor = new TMP117Full(connection);                                     // Create TMP117 Full driver, (connection)
    await sensor.init();                                                           // Confirm device identity, () → None

    // --- Lowest-noise continuous conversion ---
    // 64-conversion averaging with a 1 s cycle gives the quietest result the
    // chip can deliver — a cold-chain log needs stability, not speed.
    await sensor.configure('continuous', 64, 1.0);                                 // Configure conversion, (mode='continuous', averaging=8, cycleSeconds=1.0 s) → None

    // --- One-time calibration against a reference thermometer ---
    // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
    // the correction survives power cycles. EEPROM endurance is limited — this
    // is a once-per-deployment step, not a loop.
    if (REFERENCE_C !== null) {
        await sleep(1100);
        const offset = REFERENCE_C - (await sensor.readTemperature())              // Read temperature, () → number °C
            + (await sensor.getTemperatureOffset());                               // Read calibration offset, () → number °C
        await sensor.unlockEeprom();                                               // Unlock EEPROM, () → None
        await sensor.setTemperatureOffset(offset);                                 // Set calibration offset, (celsius °C) → None
        while (await sensor.isEepromBusy()) {                                      // Check EEPROM busy, () → boolean
            await sleep(1);
        }
        await sensor.lockEeprom();                                                 // Lock EEPROM, () → None
        console.log(`calibrated, offset ${offset.toFixed(4)} °C`);
    }

    // --- Program the safe transport range ---
    // Alert mode flags either side of the window independently; ALERT is
    // active-low open-drain, pulled up on the board.
    await sensor.setHighLimit(8.0);                                                // Set THIGH_LIMIT, (celsius °C) → None
    await sensor.setLowLimit(-25.0);                                               // Set TLOW_LIMIT, (celsius °C) → None
    await sensor.configureAlert('alert', 'active_low');                            // Configure ALERT, (mode='alert', polarity='active_low', pinFunction='alert') → None

    let alerts = 0;
    let done;
    const finished = new Promise((r) => { done = r; });

    // --- Report which boundary tripped ---
    // The status mask comes from CONFIGURATION's alert flags; reading them
    // clears them in Alert mode, re-arming the pin for the next excursion.
    await sensor.onInterrupt(async (status) => {                                   // Subscribe to ALERT, (callback) → None
        if (!status) return;
        const t = await sensor.readTemperature();                                  // Read temperature, () → number °C
        if (status & TMP117Full.SOURCE_HIGH) console.log(`${t.toFixed(2)} °C  too warm — cargo above 8 °C`);
        else if (status & TMP117Full.SOURCE_LOW) console.log(`${t.toFixed(2)} °C  too cold — cargo below -25 °C`);
        if (++alerts >= MAX_ALERTS) done();
    });
    console.log(`monitoring, ${(await sensor.readTemperature()).toFixed(2)} °C now`);  // Read temperature, () → number °C
    await finished;

    // --- Stop monitoring after the demo run ---
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
    await connection.close();
})();
