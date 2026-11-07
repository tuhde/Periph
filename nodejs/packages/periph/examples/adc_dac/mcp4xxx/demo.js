'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Mcp4xxxFull } = require('../../../src/chips/adc_dac/mcp4xxx');

const PART = process.env.MCP4XXX_PART || 'MCP4532';   // any of MCP4531 ... MCP4662
const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x58', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const pot = new Mcp4xxxFull(connection, PART, 10000.0, 75.0);  // Create MCP4XXX driver, (connection, part='MCP4532', rAb=10000 Ω, rWiper=75 Ω, hvc=null, hvcSettleMs=1) → Mcp4xxxFull

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

(async () => {
    await pot.init();                                       // Bus check, () → void

    // --- Identify the family member ---
    // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
    console.log(PART, pot.channels(), 'network(s)', pot.steps(), 'steps',   // Part properties, () → int
        pot.isPotentiometer() ? 'pot' : 'rheostat', pot.isNonvolatile() ? 'EEPROM' : 'RAM');
    console.log('power-up wiper', await pot.getWiper(), await pot.getResistance(), 'ohm');  // Read wiper, (channel=0) → int

    // --- Set 2.2 kΩ and report the quantisation error ---
    // The wiper snaps to the nearest step of RAB / max code.
    await pot.setResistance(2200.0);                        // Set W-B resistance, (ohms, channel=0) → void
    const actual = await pot.getResistance();               // Read W-B resistance, (channel=0) → float Ω
    console.log('target=2200 ohm  code=%d  actual=%s ohm  error=%s ohm', await pot.getWiper(), actual.toFixed(1), (actual - 2200).toFixed(1));

    // --- Step the wiper up and down ---
    // increment / decrement send one command byte per step in a single transaction and
    // saturate at full scale and zero scale.
    for (const [steps, up] of [[8, true], [8, true], [4, false], [10, true]]) {
        const code = up ? await pot.increment(steps) : await pot.decrement(steps);  // Step wiper, (steps=1, channel=0) → int
        console.log('%s%d -> code=%d  R_WB=%s ohm', up ? '+' : '-', steps, code, (await pot.getResistance()).toFixed(1));
    }

    // --- Ratios ---
    // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
    for (const ratio of [0.0, 0.25, 0.5, 0.75, 1.0]) {
        await pot.setRatio(ratio);                          // Set wiper ratio, (ratio 0.0-1.0, channel=0) → void
        let line = `ratio=${ratio.toFixed(2)}  code=${await pot.getWiper()}  R_WB=${(await pot.getResistance()).toFixed(1)}`;
        if (pot.isPotentiometer()) line += `  R_WA=${(await pot.getResistanceWa()).toFixed(1)}`;  // Read W-A resistance, (channel=0) → float Ω
        console.log(line);
    }

    // --- Dual parts: drive both networks ---
    if (pot.channels() === 2) {
        await pot.setBoth(32, 96);                          // Set both wipers, (code0, code1) → void
        console.log('both:', await pot.getWiper(0), await pot.getWiper(1), '->', await pot.increment(5, 1), await pot.decrement(2, 1));
    }

    // --- Shutdown and terminal disconnect ---
    // Shutdown ties W to B and opens A without losing the wiper; setTerminals(true, false, true) floats W.
    await pot.shutdown();                                   // Shut network down, (channel=0) → void
    console.log('shutdown=%s  code=%d', await pot.isShutdown(), await pot.getWiper());  // Read state, (channel=0) → bool
    await sleep(1000);
    await pot.resume();                                     // Leave shutdown, (channel=0) → void
    await pot.setTerminals(true, false, true);              // Disconnect the wiper, (a=true, w=true, b=true, channel=0) → void
    console.log('terminals', await pot.getTerminals());     // Read terminals, (channel=0) → {a, w, b}
    await pot.setTerminals(true, true, true);               // Reconnect all terminals, (a=true, w=true, b=true, channel=0) → void

    // --- Non-volatile parts: EEPROM wiper and data ---
    // storeWiper makes the setting survive power cycles; every EEPROM write uses one of the
    // 1 000 000 write cycles, so do not do this in a loop.
    if (pot.isNonvolatile()) {
        console.log(await pot.readStatus());                // Read STATUS, () → {eepromBusy, wiperLock, writeProtect}
        console.log('stored', await pot.storeWiper());      // Store the wiper in EEPROM, (channel=0) → int
        await pot.writeData(0, 0x155);                      // Write calibration marker, (index 0-9, value 0-0x1FF) → void
        console.log('data0', (await pot.readData(0)).toString(16), 'nv wiper', await pot.getNvWiper());  // Read back, (index) → int
    }

    // --- Back to the power-up state ---
    await pot.reset();                                      // Emulate power-on reset, () → void
    console.log('after reset:', await pot.getWiper());
})();
