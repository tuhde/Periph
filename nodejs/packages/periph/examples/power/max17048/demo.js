'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MAX17048Full } = require('../../../src/chips/power/max17048');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(1, 0x36);              // Create I²C connection, (bus, addr=0x36) → Connection
    const gauge = new MAX17048Full(connection, 1);              // Create MAX17048 driver, single Li-ion/LiPo cell

    console.log(`version 0x${(await gauge.version()).toString(16)}, id 0x${(await gauge.chipId()).toString(16)}`);
    await gauge.clearStatus(MAX17048Full.SOURCE_RESET);         // Clear the reset indicator, (mask) → Promise<void>

    // Battery fuel gauge: one line per second for 30 s
    for (let n = 0; n < 30; n++) {
        const v = await gauge.voltage();
        const p = await gauge.percent();
        const r = await gauge.chargeRate();
        console.log(`${v.toFixed(3)} V  ${p.toFixed(1)} %  ${r.toFixed(1)} %/h`);
        await sleep(1000);
    }

    await gauge.setAlertSoc(10);                                // Low-SOC alert at 10 %, (percent) → Promise<void>
    await gauge.setVoltageAlert(3.3, 4.25);                     // Voltage window 3.3-4.25 V, (minV, maxV) → Promise<void>
    await gauge.onInterrupt((status) => console.log('alert 0x' + status.toString(16)));  // Subscribe to ALRT, (callback, {intPin}) → Promise<void>
    await sleep(5000);
    await gauge.offInterrupt();
    console.log('poll_interrupt:', await gauge.pollInterrupt()); // Without an ALRT pin, () → Promise<number>

    for (const celsius of [5, 35]) {
        await gauge.compensateTemperature(celsius);             // RCOMP for the temperature, (celsius) → Promise<void>
        console.log(`RCOMP at ${celsius} C: 0x${(await gauge.getRcomp()).toString(16)}`);
    }
    await gauge.compensateTemperature(20);

    await gauge.disableHibernate();                             // Never hibernate, () → Promise<void>
    console.log('hibernating:', await gauge.isHibernating());
    await gauge.setHibernateThresholds(26.624, 0.06);           // Back to the defaults

    await gauge.enableSleep(true);                              // Sleep round trip, (enabled) → Promise<void>
    await gauge.sleep();
    await sleep(1000);
    await gauge.wake();
    await gauge.enableSleep(false);
    console.log('after wake:', (await gauge.voltage()).toFixed(3), 'V');

    if (process.argv.includes('--quick-start')) {
        await gauge.quickStart();                               // Restart the SOC estimate, () → Promise<void>
        await sleep(1000);
        console.log('SOC after quick start:', (await gauge.percent()).toFixed(1), '%');
    }
    await connection.close();
}

main();
