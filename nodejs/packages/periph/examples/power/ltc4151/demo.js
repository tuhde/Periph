'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LTC4151Full } = require('../../../src/chips/power/ltc4151');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const connection = new I2CConnection(1, 0x6A);                            // Create I²C connection, (bus=1, addr=0x6A) → Connection
const ltc = new LTC4151Full(connection, 0.02);                            // Create LTC4151 driver, (connection, rSense=0.02 Ω) → void

(async () => {
    // --- High-side supply monitor: continuous scan once per second for 20 s ---
    for (let n = 0; n < 20; n++) {
        const d = await ltc.readAll();                                    // Consistent read of all channels, () → {vin, senseV, current, power, adin}
        console.log(`${d.vin.toFixed(2)} V  ${(d.senseV * 1000).toFixed(2)} mV  ${d.current.toFixed(4)} A  ${d.power.toFixed(3)} W  adin ${d.adin.toFixed(3)} V`);
        await sleep(1000);
    }

    // --- Compare snapshot conversion times: SENSE ~67 ms, VIN / ADIN ~33 ms ---
    for (const [name, ch] of [['SENSE', LTC4151Full.SENSE], ['VIN', LTC4151Full.VIN], ['ADIN', LTC4151Full.ADIN]]) {
        const t0 = Date.now();
        const value = await ltc.snapshot(ch);                             // One snapshot conversion, (channel 0–2) → number V
        console.log(`${name} snapshot ${value.toFixed(4)} V in ${Date.now() - t0} ms`);
    }
    console.log('snapshot mode after snapshot:', await ltc.isSnapshotMode());   // Snapshot mode active, () → boolean

    // --- Toggle the stuck-bus timer and page read/write ---
    await ltc.setStuckBusTimer(false);                                    // Stuck-bus reset timer, (enabled) → void
    await ltc.setPageRw(false);                                           // I²C page read/write, (enabled) → void
    console.log('CONTROL (timer off, page off): 0x' + (await ltc.readControl()).toString(16));   // Read CONTROL, () → number
    await ltc.setStuckBusTimer(true);
    await ltc.setPageRw(true);
    console.log('CONTROL (restored): 0x' + (await ltc.readControl()).toString(16));

    // --- NTC temperature from a made-up ADIN / VIN code pair ---
    console.log('temperature', LTC4151Full.temperatureFromAdin(900, 1200).toFixed(1), '°C');   // NTC example circuit, (adinCode, vinCode) → number °C

    // --- 5 s min / max / average power tally ---
    const samples = [];
    const end = Date.now() + 5000;
    while (Date.now() < end) {
        samples.push(await ltc.power());                                  // Read load power, () → number W
        await sleep(200);
    }
    const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
    console.log(`power min ${Math.min(...samples).toFixed(3)} W, max ${Math.max(...samples).toFixed(3)} W, avg ${avg.toFixed(3)} W`);

    await connection.close();
})();
