'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LC709203FFull } = require('../../../src/chips/power/lc709203f');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(1, 0x0B);              // Create I²C connection, (bus, addr=0x0B) → Connection
    const gauge = new LC709203FFull(connection, 1000, 1);       // Create LC709203F driver, 1000 mAh 3.7 V LiPo
    await gauge.ready;

    console.log(`IC version 0x${(await gauge.icVersion()).toString(16)}, profile id 0x${(await gauge.profileId()).toString(16)}, RSOC ${await gauge.rsoc()} %`);

    await gauge.setAlarmRsoc(20);                               // Alarm at 20 % RSOC, (percent) → Promise<void>
    await gauge.setAlarmVoltage(3.5);                           // Alarm at 3.5 V, (volts) → Promise<void>

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    for (let n = 0; n < 12; n++) {
        await gauge.setTemperature(25.0);
        const v = await gauge.voltage();
        const p = await gauge.percent();
        const t = await gauge.temperature();
        console.log(`${v.toFixed(3)} V  ${p.toFixed(1)} %  ${t.toFixed(1)} °C  alarm=${await gauge.pollInterrupt()}`);
        await sleep(5000);
    }

    await gauge.sleep();                                        // Sleep mode, () → Promise<void>
    await gauge.wake();                                         // Wake up, () → Promise<void>
    console.log('sleeping:', await gauge.isSleeping());
    await connection.close();
}

main();
