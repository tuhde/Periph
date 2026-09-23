'use strict';

// Exercises every method in the TMP117 Full API. EEPROM unlock/lock is shown
// without any write in between, so no power-on default is changed.

const { I2CConnection } = require('periph/src/connection/i2c');
const { TMP117Full } = require('periph/src/chips/temperature/tmp117');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TMP117Full.I2C_ADDRESS);
    const sensor = new TMP117Full(connection);                                     // Create TMP117 Full driver, (connection)
    await sensor.init();                                                           // Confirm device identity, () → None
                                                                                   // checks DEVICE_ID bits 11:0 == 0x117

    const t = await sensor.readTemperature();                                      // Read temperature, () → number °C
                                                                                   // decodes TEMP_RESULT, 0.0078125 °C two's complement
    console.log(`temperature ${t.toFixed(4)} °C`);

    await sensor.configure('continuous', 32, 0.5);                                 // Configure conversion, (mode='continuous', averaging=8, cycleSeconds=1.0 s) → None
                                                                                   // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
    console.log('config', await sensor.getConfig());                               // Read conversion config, () → {mode, averaging, cycleSeconds s}
                                                                                   // decodes MOD, AVG and CONV from CONFIGURATION

    await sensor.configure('shutdown');                                            // Configure conversion, (mode='continuous', averaging=8, cycleSeconds=1.0 s) → None
                                                                                   // MOD=01 stops conversions; TEMP_RESULT keeps its last value
    console.log('shutdown', await sensor.isShutdown());                            // Check Shutdown mode, () → boolean
                                                                                   // reads MOD[1:0] == 01
    await sensor.triggerOneShot();                                                 // Start one conversion, () → None
                                                                                   // MOD=11; returns to Shutdown when done
    while (!(await sensor.isDataReady())) {                                        // Check for a fresh result, () → boolean
        await sleep(10);                                                           // reading Data_Ready clears it
    }
    console.log(`one-shot ${(await sensor.readTemperature()).toFixed(4)} °C`);     // Read temperature, () → number °C
                                                                                   // the one-shot result
    await sensor.configure();                                                      // Configure conversion, (mode='continuous', averaging=8, cycleSeconds=1.0 s) → None
                                                                                   // back to the POR default

    await sensor.setHighLimit(30.0);                                               // Set THIGH_LIMIT, (celsius °C) → None
                                                                                   // rounded to the nearest 0.0078125 °C step
    await sensor.setLowLimit(10.0);                                                // Set TLOW_LIMIT, (celsius °C) → None
                                                                                   // rounded to the nearest 0.0078125 °C step
    console.log('high', await sensor.getHighLimit());                              // Read THIGH_LIMIT, () → number °C
                                                                                   // same format as TEMP_RESULT
    console.log('low', await sensor.getLowLimit());                                // Read TLOW_LIMIT, () → number °C
                                                                                   // same format as TEMP_RESULT

    await sensor.setTemperatureOffset(0.25);                                       // Set calibration offset, (celsius °C) → None
                                                                                   // added to every result after linearization
    console.log('offset', await sensor.getTemperatureOffset());                    // Read calibration offset, () → number °C
                                                                                   // decodes TEMP_OFFSET
    await sensor.setTemperatureOffset(0.0);                                        // Set calibration offset, (celsius °C) → None
                                                                                   // remove the offset again

    await sensor.unlockEeprom();                                                   // Unlock EEPROM, () → None
                                                                                   // EUN=1: EEPROM-backed writes now persist
    console.log('eeprom busy', await sensor.isEepromBusy());                       // Check EEPROM busy, () → boolean
                                                                                   // reads EEPROM_UL.EEPROM_Busy
    await sensor.lockEeprom();                                                     // Lock EEPROM, () → None
                                                                                   // EUN=0: writes are volatile again
    const eeprom1 = await sensor.readEepromScratch(1);                             // Read EEPROM scratch, (slot 1|2|3) → number
                                                                                   // slot 1 holds part of the factory unique ID
    console.log(`eeprom1 0x${eeprom1.toString(16).padStart(4, '0')}`);
    await sensor.writeEepromScratch(2, 0x1234);                                    // Write EEPROM scratch, (slot 2, value 16-bit) → None
                                                                                   // only EEPROM2 is writable; volatile while locked
    const eeprom2 = await sensor.readEepromScratch(2);                             // Read EEPROM scratch, (slot 1|2|3) → number
                                                                                   // reads back EEPROM2
    console.log(`eeprom2 0x${eeprom2.toString(16).padStart(4, '0')}`);

    await sensor.configureAlert('alert', 'active_low', 'alert');                   // Configure ALERT, (mode='alert', polarity='active_low', pinFunction='alert') → None
                                                                                   // sets T/nA, POL and DR/Alert together

    const status = await sensor.pollInterrupt();                                   // Read alert flags, () → number mask
                                                                                   // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
    console.log('above high', (status & TMP117Full.SOURCE_HIGH) !== 0,
        'below low', (status & TMP117Full.SOURCE_LOW) !== 0);

    await sensor.onInterrupt((s) => console.log('alert, status mask', s));         // Subscribe to ALERT, (callback) → None
                                                                                   // callback receives the pollInterrupt() mask
    await sleep(5000);
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
                                                                                   // detaches the edge handler or stops the polling timer

    await sensor.reset();                                                          // Software reset, () → None
                                                                                   // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms

    await connection.close();
})();
