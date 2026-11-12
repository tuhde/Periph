'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CS43L22Full, BEEP } = require('../../../src/chips/adc_dac/cs43l22');

async function main() {
    const connection = new I2CConnection(1, 0x4A);                     // Create I2C connection, (bus=1, addr=0x4A)

    // --- Bring up the headphone amplifier ---
    // The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
    // I2S stream come from the board's I2S master. init() loads the I2S-slave
    // defaults while the chip is powered down; start() powers it up once MCLK runs.
    const dac = new CS43L22Full(connection);                           // Create CS43L22 Full driver, (connection, resPin=null)
    await dac.init();                                                  // Reset, verify ID, load defaults, () → Promise<void>
    console.log(await dac.readId());                                   // Read chip ID and revision, () → Promise<{chipId, revision}>
    await dac.setVolume(-60.0);                                        // Set master volume, (db −102–12 dB) → Promise<void>
    await dac.start();                                                 // Power up the DAC once MCLK is running, () → Promise<void>

    // --- Wait for the serial port to lock ---
    // SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
    // reading the status register clears the flags.
    for (let i = 0; i < 50; i++) {
        const status = await dac.readStatus();                             // Read status flags (clears them), () → Promise<object>
        if (!status.clockError) break;
        await new Promise((resolve) => setTimeout(resolve, 100));
    }

    // --- Ramp the volume up ---
    // 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
    // smooths each step further.
    for (let vol = -60; vol <= 0; vol += 2) {
        await dac.setVolume(vol);                                          // Set master volume, (db −102–12 dB) → Promise<void>
        await new Promise((resolve) => setTimeout(resolve, 100));
    }

    // --- Shape the tone and protect the headphones ---
    // +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
    // loud peaks from clipping the amplifier.
    await dac.setTone(6.0, 6.0);                                       // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → Promise<void>
    await dac.setLimiter(true, { maxDb: -6, cushionDb: -12 });         // Configure the peak limiter, (enabled, {maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false}) → Promise<void>

    // --- Play a single beep ---
    // The beep generator needs no audio data; it mixes into the output once MCLK
    // and LRCK are running. Frequency code 0x1 is 521.74 Hz.
    await dac.setBeep(0x1, 2, 0, -6.0, BEEP.SINGLE);                   // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → Promise<void>
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await dac.stopBeep();                                              // Stop the beep generator, () → Promise<void>

    // --- Power down safely ---
    // stop() mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
    // only then may the application remove MCLK.
    await dac.stop();                                                  // Power down the DAC, () → Promise<void>
    console.log('MCLK may now be removed');
}

main();
