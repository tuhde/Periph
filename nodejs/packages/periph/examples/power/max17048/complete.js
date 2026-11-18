'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MAX17048Full } = require('../../../src/chips/power/max17048');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(1, 0x36);              // Create I²C connection, (bus, addr=0x36) → Connection
    const gauge = new MAX17048Full(connection, 1);              // Create MAX17048 driver, (connection, cells)
                                                                // cells = 1 (MAX17048) or 2 (MAX17049)

    console.log(await gauge.voltage());                         // Cell voltage, () → Promise<number> V
    console.log(await gauge.percent());                         // State of charge, () → Promise<number> %
    console.log(await gauge.rawVoltage());                      // VCELL register, () → Promise<number>
    console.log(await gauge.rawSoc());                          // SOC register, () → Promise<number>
    console.log(await gauge.chargeRate());                      // Charge/discharge rate, () → Promise<number> %/h
    console.log(await gauge.version());                         // VERSION register, () → Promise<number>
    console.log(await gauge.chipId());                          // Factory ID, () → Promise<number>

    await gauge.setRcomp(0x97);                                 // RCOMP, (value) → Promise<void>
    console.log(await gauge.getRcomp());                        // () → Promise<number>
    await gauge.compensateTemperature(25.0);                    // RCOMP from the temperature, (celsius) → Promise<void>

    await gauge.setAlertSoc(10);                                // Empty alert threshold, (percent) → Promise<void>; 1-32 %
    console.log(await gauge.getAlertSoc());                     // () → Promise<number> %
    await gauge.setSocChangeAlert(true);                        // Alert on 1 % SOC change, (enabled) → Promise<void>
    await gauge.setVoltageAlert(3.3, 4.25);                     // Voltage window, (minV, maxV) → Promise<void>
                                                                // 0 / 5.1 V disable the respective limit
    console.log(await gauge.getVoltageAlert());                 // () → Promise<{minV, maxV}>
    await gauge.setResetVoltage(3.0);                           // Reset voltage, (volts) → Promise<void>
    console.log(await gauge.getResetVoltage());                 // () → Promise<number> V
    await gauge.enableVoltageResetAlert(true);                  // Voltage reset asserts ALRT, (enabled) → Promise<void>
    await gauge.setComparatorDisabled(false);                   // Hibernate comparator off, (disabled) → Promise<void>

    await gauge.setHibernateThresholds(26.624, 0.06);           // Hibernate thresholds, (hibThrPctPerH, actThrV) → Promise<void>
    console.log(await gauge.getHibernateThresholds());          // () → Promise<{hibThrPctPerH, actThrV}>
    await gauge.disableHibernate();                             // Never hibernate, () → Promise<void>
    await gauge.forceHibernate();                               // Always hibernate, () → Promise<void>
    await gauge.setHibernateThresholds(26.624, 0.06);           // Restore the defaults
    console.log(await gauge.isHibernating());                   // Hibernate status, () → Promise<boolean>

    console.log(await gauge.readStatus());                      // STATUS register, () → Promise<number>
    await gauge.clearStatus(MAX17048Full.SOURCE_RESET);         // Clear flags and ALRT, (mask) → Promise<void>
    console.log(await gauge.readConfig());                      // CONFIG register, () → Promise<number>
    await gauge.writeConfig(await gauge.readConfig());          // Write CONFIG, (value) → Promise<void>

    console.log(await gauge.pollInterrupt());                   // Alert sources, () → Promise<number>
                                                                // SOURCE_RESET / _OVERVOLTAGE / _UNDERVOLTAGE
                                                                // / _VRESET / _LOW_SOC / _SOC_CHANGE
    await gauge.enableInterrupt(MAX17048Full.SOURCE_SOC_CHANGE);  // Enable a source, (source) → Promise<void>
    await gauge.disableInterrupt(MAX17048Full.SOURCE_SOC_CHANGE); // Disable a source, (source) → Promise<void>
    await gauge.onInterrupt((status) => console.log('alert', status.toString(16)));  // Subscribe to ALRT, (callback, {intPin}) → Promise<void>
    await sleep(2000);
    await gauge.offInterrupt();                                 // Unsubscribe, () → Promise<void>

    await gauge.enableSleep(true);                              // Allow sleep mode, (enabled) → Promise<void>
    await gauge.sleep();                                        // Sleep, () → Promise<void>
    await gauge.wake();                                         // Wake up, () → Promise<void>
    await gauge.enableSleep(false);

    await gauge.quickStart();                                   // Restart the SOC estimate, () → Promise<void>
                                                                // only if the first SOC is clearly wrong
    await gauge.reset();                                        // Power-on reset, () → Promise<void>; waits 1 s
    await connection.close();
}

main();
