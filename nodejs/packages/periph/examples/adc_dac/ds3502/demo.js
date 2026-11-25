'use strict';

// Demo: a VCOM-style voltage setter. With RH = 15.0 V and RL = 0 V (V+ = 15.5 V)
// prints the wiper loaded from the IVR at power-up, sets 6.0 V, sweeps the
// wiper in 16 steps, steps up and down, shows the MODE bit and finally
// persists the chosen code in the EEPROM (consuming one EEPROM write).

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3502Full } = require('periph/src/chips/adc_dac/ds3502');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS3502Full.I2C_ADDRESS);
    const pot = new DS3502Full(connection, 15.0, 0.0);                      // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    await pot.init();                                                       // Run initialization sequence, () → void (CR = 0x80, caches WR)

    // --- Power-up position ---
    // init leaves the wiper where the IVR put it (factory default 0x40).
    console.log('power-up wiper', await pot.getWiper(), '->', await pot.getVoltage(), 'V');// Read wiper position, () → int 0–127

    // --- Set 6.0 V ---
    await pot.setVoltage(6.0);                                              // Set RW output voltage, (volts V) → void
    console.log('code', await pot.getWiper(), 'ratio', (await pot.getRatio()).toFixed(3), 'voltage', (await pot.getVoltage()).toFixed(3), 'V');// Read divider ratio, () → float

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for (let code = 0; code <= 127; code += 8) {
        await pot.setWiper(code);                                           // Set wiper position, (code 0–127) → void
        console.log('code', code, '->', (await pot.getVoltage()).toFixed(3), 'V');// Read unloaded RW voltage, () → float V
        await sleep(200);
    }

    // --- Step up and down ---
    await pot.setVoltage(6.0);
    console.log('+4 ->', await pot.increment(4));                           // Step the wiper, (steps=1) → int 0–127
    console.log('-4 ->', await pot.increment(-4));

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    console.log('eeprom mode:', await pot.getMode());                       // Read MODE, () → bool
    await pot.setMode(false);                                               // Select SRAM-only writes, (eeprom bool) → void

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    const code = await pot.getWiper();
    await pot.storeWiper(code);                                             // Store power-up position, (code) → void
    await pot.waitReady();                                                  // Acknowledge polling, (timeoutMs=30) → void
    console.log('read back', await pot.getWiper());

    await connection.close();
})();
