'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AD5248Full } = require('../../../src/chips/adc_dac/ad5248');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x2C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const pot = new AD5248Full(connection, 10000.0, 160.0);     // Create AD5248 driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Full

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

(async () => {
    // --- Sweep channel 0 across its full range ---
    // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
    // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
    for (let code = 0; code <= 255; code += 16) {
        await pot.setWiper(0, code);                        // Set channel 0 wiper, (channel 0-1, code 0-255) → void
        const ohms = await pot.getResistance(0);            // Read W-B resistance, (channel 0-1) → float Ω
        console.log('code=%d  R_WB=%s ohm', code, ohms.toFixed(1));
        await sleep(100);
    }

    // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
    // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
    await pot.setResistance(1, 2200.0);                     // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → void
    const actual = await pot.getResistance(1);              // Read back W-B resistance, (channel 0-1) → float Ω
    console.log('ch1 target=2200 ohm  actual=%s ohm  error=%s ohm', actual.toFixed(1), (actual - 2200.0).toFixed(1));

    // --- Shutdown and resume channel 1 ---
    // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
    await pot.shutdown(1);                                  // Shut channel 1 down, (channel 0-1) → void
    console.log('ch1 shutdown=%s  code=%d', pot.isShutdown(1), await pot.getWiper(1));  // Query state, (channel 0-1) → bool
    await sleep(1000);
    await pot.resume(1);                                    // Resume channel 1, (channel 0-1) → void

    // --- Restore the power-up preset ---
    await pot.reset();                                      // Reset both channels to midscale, () → void
    console.log('after reset: ch0=%d ch1=%d', await pot.getWiper(0), await pot.getWiper(1));
    await connection.close();
})();
