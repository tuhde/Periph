'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ES8311Full } = require('../../../src/chips/adc_dac/es8311');

async function main() {
    const connection = new I2CConnection(1, 0x18);                       // Create I2C connection, (bus=1, addr=0x18)
    const codec = new ES8311Full(connection, 12288000, 48000);           // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz)
                                                                         // reset, clock dividers, I2S 16-bit slave, analog path, mic 24 dB, DAC silent

    await codec.setVolume(-30.0);                                        // Set DAC volume, (db −95.5–32 dB) → Promise<void>
                                                                         // writes 0x32 = round(db / 0.5) + 191
    console.log(await codec.getVolume());                                // Read back DAC volume, () → Promise<float dB>
                                                                         // (0x32 − 191) × 0.5
    await codec.setMute(false);                                          // Unmute the DAC, (muted=false) → Promise<void>
                                                                         // clears DSM and DEM mute bits of 0x31
    await codec.setMicGain(27);                                          // Set microphone PGA gain, (db 0–30 dB) → Promise<void>
                                                                         // 0x14 bits 3:0 = round(db / 3)
    console.log(await codec.getMicGain());                               // Read back PGA gain, () → Promise<float dB>
                                                                         // 3 dB per register step
    await codec.configureClock(12288000, 16000);                         // Program the clock tree, (mclkHz Hz, sampleRateHz Hz, mclkFromBclk=false, mclkInvert=false) → Promise<void>
                                                                         // preset table first, then computed pre-divider / multiplier / OSR
    await codec.configureSerial({ format: 'i2s', wordLength: 16 });      // Configure the digital audio port, ({format='i2s', wordLength=16, master=false, bclkInvert=false, lrckInvert=false, dacChannel='left', bclkDiv, lrckDiv, continuousBclk=true}) → Promise<void>
                                                                         // 0x09 / 0x0A format and word length, slave mode
    await codec.setTristate(false, false);                               // Tri-state BCLK/LRCK and ADCDAT, (bclkLrck=false, adcdat=false) → Promise<void>
                                                                         // 0x07 bits 5:4
    await codec.setInput('analog');                                      // Select microphone input, (source='analog'|'digital'|'none') → Promise<void>
                                                                         // MIC1P / MIC1N differential, PGA powered
    await codec.configureDmic(false);                                    // Select PDM latch edge, (latchFallingEdge=false) → Promise<void>
                                                                         // 0x15 bit 0
    await codec.setAdcVolume(0);                                         // Set ADC digital volume, (db −95.5–32 dB) → Promise<void>
                                                                         // 0x17; ALC maximum gain when the ALC is on
    await codec.configureAdc({ scaleDb: 24, osr: 64, hpf: true });       // Configure ADC path, ({scaleDb=24 dB, osr=64, doubleSpeed=false, hpf=true, hpfCoeffs=[0x0C, 0x0C]}) → Promise<void>
                                                                         // 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
    await codec.setAdcRamp(0);                                           // Set ADC soft-ramp rate, (rate 0–15) → Promise<void>
                                                                         // 0 = off; n = 0.25 dB per 2^(n+1) LRCK
    await codec.configureAlc(true, { maxLevelDb: -12.0, minLevelDb: -18.1, automute: true, noiseGateDb: -60 }); // Configure ALC, (enabled, {maxLevelDb, minLevelDb, window, automute, automuteWindow, noiseGateDb}) → Promise<void>
                                                                         // 0x18–0x1A, levels rounded to the nearest listed value
    await codec.configureAlc(false);                                     // Disable ALC, (enabled=false) → Promise<void>
                                                                         // restores defaults
    await codec.setAdcEq(null);                                          // Bypass ADC equalizer, (coefficients=null) → Promise<void>
                                                                         // 0x1C bit 6; an array of five 30-bit values loads b0,a1,a2,b1,b2
    await codec.setDacRamp(8);                                           // Set DAC soft-ramp rate, (rate 0–15) → Promise<void>
                                                                         // 0x37 bits 7:4
    await codec.configureDrc(true, { maxLevelDb: -12.0, minLevelDb: -24.1 }); // Configure DRC, (enabled, {maxLevelDb, minLevelDb, window}) → Promise<void>
                                                                         // 0x34 / 0x35; DAC volume becomes the maximum gain
    await codec.configureDrc(false);                                     // Disable DRC, (enabled=false) → Promise<void>
                                                                         // clears the DRC enable bit
    await codec.setDacEq(null);                                          // Bypass DAC equalizer, (coefficients=null) → Promise<void>
                                                                         // 0x37 bit 3; an array of three 30-bit values loads b0,b1,a1
    await codec.setDacMuteOptions(true, true, false);                    // Set DAC mute bits, (dsm=true, dem=true, dsmTarget=false) → Promise<void>
                                                                         // 0x31 bits 7:5 individually
    await codec.setLoopback(true);                                       // Route ADC output into the DAC, (enabled) → Promise<void>
                                                                         // 0x44 bit 7
    await codec.setLoopback(false);                                      // Disable loopback, (enabled=false) → Promise<void>
                                                                         // 0x44 bit 7 cleared
    await codec.setAdcdatMode('dac_adc');                                // Select ADCDAT content, (mode='adc_adc'|'adc_zero'|'zero_adc'|'dac_adc'|'adc_dac'|'dac_dac') → Promise<void>
                                                                         // DAC + ADC: digital feedback for echo cancellation
    await codec.setAdcdatMode('adc_adc');                                // Restore ADCDAT content, (mode='adc_adc') → Promise<void>
                                                                         // ADC data on both slots
    await codec.setSdpMute(false, false);                                // Mute serial input / output, (input=false, output=false) → Promise<void>
                                                                         // 0x09 / 0x0A bit 6
    await codec.setLowPower({});                                         // Clear all low-power bits, ({dac, pga, pgaOut, adc, adcRef, dacRef, flash, integrator}) → Promise<void>
                                                                         // 0x0F; all off = best THD+N
    console.log(await codec.readRegister(0x32));                         // Read raw register, (register 0x00–0xFF) → Promise<int>
                                                                         // returns the byte as stored in the chip
    await codec.writeRegister(0x32, 0xBF);                               // Write raw register, (register, value) → Promise<void>
                                                                         // 0xBF = 0 dB DAC volume
    await new Promise((resolve) => setTimeout(resolve, 1000));
    await codec.setMute(true);                                           // Mute the DAC, (muted=true) → Promise<void>
                                                                         // sets DSM and DEM mute
    await codec.powerDown();                                             // Enter standby, () → Promise<void>
                                                                         // powers down analog blocks, resets digital, clears CSM_ON
    await codec.powerUp();                                               // Leave standby, () → Promise<void>
                                                                         // re-enables analog blocks, clocks and CSM_ON
    await codec.reset();                                                 // Software reset, () → Promise<void>
                                                                         // registers return to reset values; reconfigure afterwards
}

main();
