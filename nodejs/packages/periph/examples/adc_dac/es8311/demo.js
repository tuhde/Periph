'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ES8311Full } = require('../../../src/chips/adc_dac/es8311');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
    // --- Bring up the codec for a voice assistant ---
    // The control port only configures the codec; the I2S stream (MCLK, BCLK,
    // LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
    // speech recognition.
    const connection = new I2CConnection(1, 0x18);                       // Create I2C connection, (bus=1, addr=0x18)
    const codec = new ES8311Full(connection, 12288000, 48000);           // Create ES8311 driver, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz)
    await codec.configureClock(12288000, 16000);                         // Program the clock tree, (mclkHz Hz, sampleRateHz Hz) → Promise<void>
    await codec.configureSerial({ format: 'i2s', wordLength: 16 });      // Configure the digital audio port, ({format='i2s', wordLength=16}) → Promise<void>

    // --- Configure the microphone path ---
    // 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
    // level; the noise gate and automute silence the line between utterances.
    await codec.setMicGain(24);                                          // Set microphone PGA gain, (db 0–30 dB) → Promise<void>
    console.log('mic gain', await codec.getMicGain());                   // Read back PGA gain, () → Promise<float dB>
    await codec.setAdcVolume(0);                                         // Set ADC digital volume, (db −95.5–32 dB) → Promise<void>
    await codec.configureAlc(true, { maxLevelDb: -12.0, minLevelDb: -18.1, automute: true, noiseGateDb: -60 }); // Configure ALC, (enabled, {maxLevelDb, minLevelDb, automute, noiseGateDb}) → Promise<void>
    await codec.configureAdc({ hpf: true });                             // Configure ADC path, ({scaleDb=24 dB, osr=64, hpf=true}) → Promise<void>

    // --- Configure playback ---
    // The DRC compresses loud peaks so a small speaker does not clip; the soft
    // ramp avoids clicks while the volume changes.
    await codec.configureDrc(true, { maxLevelDb: -12.0, minLevelDb: -24.1 }); // Configure DRC, (enabled, {maxLevelDb, minLevelDb}) → Promise<void>
    await codec.setDacRamp(8);                                           // Set DAC soft-ramp rate, (rate 0–15) → Promise<void>
    await codec.setMute(false);                                          // Unmute the DAC, (muted=false) → Promise<void>

    // --- Ramp the playback volume ---
    // Step from -60 dB to -10 dB, reading the value back each time.
    for (let vol = -60; vol <= -10; vol += 10) {
        await codec.setVolume(vol);                                      // Set DAC volume, (db −95.5–32 dB) → Promise<void>
        console.log('volume', await codec.getVolume(), 'dB');            // Read back DAC volume, () → Promise<float dB>
        await sleep(500);
    }

    // --- Echo-cancellation feedback and loopback ---
    // "dac_adc" puts the DAC data next to the microphone on ADCDAT so the host
    // can cancel the speaker echo; the loopback routes the microphone to the
    // speaker for a quick acoustic check.
    await codec.setAdcdatMode('dac_adc');                                // Select ADCDAT content, (mode) → Promise<void>
    await sleep(2000);
    await codec.setAdcdatMode('adc_adc');                                // Restore ADCDAT content, (mode) → Promise<void>
    await codec.setLoopback(true);                                       // Route ADC output into the DAC, (enabled) → Promise<void>
    await sleep(2000);
    await codec.setLoopback(false);                                      // Disable loopback, (enabled) → Promise<void>

    // --- Shut down ---
    // Mute first so power-down does not pop, then enter standby.
    await codec.setMute(true);                                           // Mute the DAC, (muted=true) → Promise<void>
    await codec.powerDown();                                             // Enter standby, () → Promise<void>
}

main();
