'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ES8311Minimal } = require('../../../src/chips/adc_dac/es8311');

async function main() {
    const connection = new I2CConnection(1, 0x18);                       // Create I2C connection, (bus=1, addr=0x18)
    const codec = new ES8311Minimal(connection, 12288000, 48000);        // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz)

    await codec.setMicGain(24);                                          // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → Promise<void>
    await codec.setMute(false);                                          // Unmute the DAC, (muted=false) → Promise<void>
    await codec.setVolume(-20.0);                                        // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → Promise<void>

    await new Promise((resolve) => setTimeout(resolve, 5000));
    await codec.setMute(true);                                           // Mute the DAC, (muted=true) → Promise<void>
}

main();
