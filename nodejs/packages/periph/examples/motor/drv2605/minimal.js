'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV2605Minimal } = require('periph/src/chips/motor/drv2605');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV2605Minimal.I2C_ADDRESS);
    const haptic = new DRV2605Minimal(connection, false);                          // Create DRV2605 driver, (connection, lra=false)
    await haptic.init();                                                           // Reset, verify and configure the chip, () → None

    for (const effect of [DRV2605Minimal.EFFECT_STRONG_CLICK, DRV2605Minimal.EFFECT_DOUBLE_CLICK, DRV2605Minimal.EFFECT_BUZZ]) {
        await haptic.playEffect(effect);                                           // Play a ROM effect, (effectId 1–123) → None
        while (await haptic.isPlaying()) {                                         // Check playback, () → boolean
            await sleep(10);
        }
        await sleep(500);
    }

    await haptic.stop();                                                           // Cancel playback, () → None
    await connection.close();
})();
