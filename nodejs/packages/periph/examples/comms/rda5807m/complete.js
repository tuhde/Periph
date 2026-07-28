'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RDA5807MFull } = require('../../../src/chips/comms/rda5807m');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x10', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const fm = new RDA5807MFull(connection, 100.0, 8);       // Create RDA5807M driver, (connection, frequencyMhz=100.0, volume=8)
                                                         // runs the init sequence and tunes to the initial frequency

(async () => {
    await fm.setFrequency(97.5);                        // Tune to frequency, (frequencyMhz) → undefined
                                                         // computes CHAN from the current band/spacing and blocks until STC
    console.log(await fm.frequency());                  // Read tuned frequency, () → number MHz
                                                         // converts READCHAN back to MHz

    await fm.setVolume(10);                             // Set volume, (level 0–15) → undefined
    await fm.mute(false);                               // Mute/unmute, (enable) → undefined
                                                         // enable=true mutes; here we ensure audio is audible

    const freq = await fm.seek(true);                   // Seek next station, (up=true) → number|null
                                                         // blocks until STC; returns null if SF (seek fail) is set
    console.log(freq);

    await fm.configure({                                // Configure tuner, ({band, space, deEmphasis, seekThreshold, seekMode, clkMode, afcDisable, eastEurope50m}) → undefined
        band: RDA5807MFull.BAND_WORLD,                  // re-tunes to the current frequency if band or space changed
        space: RDA5807MFull.SPACE_100K,
        deEmphasis: true,
        seekThreshold: 8,
        seekMode: true
    });

    await fm.setBassBoost(true);                        // Enable bass boost, (enable) → undefined
    await fm.setMono(false);                            // Force mono/allow stereo, (enable) → undefined
    await fm.setSoftmute(true);                         // Enable soft mute, (enable) → undefined

    await fm.enableRds(true);                           // Enable RDS/RBDS, (enable) → undefined
    setTimeout(async () => {
        console.log(await fm.rdsReady());               // Check RDS group ready, () → bool
        console.log(await fm.readRdsGroup());           // Read raw RDS blocks, () → number[]|null

        console.log(await fm.isStereo());               // Check stereo indicator, () → bool
        console.log(await fm.isStation());               // Check real station, () → bool
        console.log(await fm.isReady());                 // Check tuner ready, () → bool
        console.log(await fm.signalStrength());          // Read RSSI, () → number 0–127

        await fm.standby(true);                          // Power down/up, (enable) → undefined
        setTimeout(async () => {
            await fm.standby(false);
            await fm.softReset();                        // Pulse soft reset, () → undefined
        }, 10);
    }, 1000);
})();
