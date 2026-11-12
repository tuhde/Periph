'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CS43L22Minimal } = require('../../../src/chips/adc_dac/cs43l22');

async function main() {
    const connection = new I2CConnection(1, 0x4A);                     // Create I2C connection, (bus=1, addr=0x4A)
    const dac = new CS43L22Minimal(connection);                        // Create CS43L22 driver, (connection, resPin=null)
    await dac.init();                                                  // Reset, verify ID, load defaults, () → Promise<void>
    await dac.start();                                                 // Power up the DAC once MCLK is running, () → Promise<void>
    await dac.setVolume(-20.0);                                        // Set master volume, (db −102–12 dB, 0.5 dB steps) → Promise<void>
    await dac.mute(false);                                             // Unmute the headphone outputs, (on=true) → Promise<void>
    for (let i = 0; i < 5; i++) {
        console.log(await dac.getVolume());                                // Read back master volume, () → Promise<number> dB
        await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    await dac.stop();                                                  // Power down the DAC, () → Promise<void>
}

main();
