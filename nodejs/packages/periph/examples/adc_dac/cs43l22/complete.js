'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CS43L22Full, clockingFor, OUT, FMT, BEEP, SRC, GAIN } = require('../../../src/chips/adc_dac/cs43l22');

async function main() {
    const connection = new I2CConnection(1, 0x4A);                     // Create I2C connection, (bus=1, addr=0x4A)
    const dac = new CS43L22Full(connection);                           // Create CS43L22 Full driver, (connection, resPin=null)
    await dac.init();                                                  // Reset, verify ID, load defaults, () → Promise<void>
                                                                       // reset pulse (if RESET pin), ID check, I2S slave defaults, required init block

    await dac.start();                                                 // Power up the DAC once MCLK is running, () → Promise<void>
                                                                       // writes POWER_CTL_1 = 0x9E
    await dac.setVolume(-30.0);                                        // Set master volume, (db −102–12 dB) → Promise<void>
                                                                       // code = round(db * 2) & 0xFF to registers 0x20 / 0x21
    console.log(await dac.getVolume());                                // Read back master volume, () → Promise<number> dB
                                                                       // signed register 0x20 / 2
    console.log(await dac.readId());                                   // Read chip ID and revision, () → Promise<{chipId, revision}>
                                                                       // register 0x01 split into bits 7:3 and 2:0
    await dac.setVolumeChannels(-20.0, -24.0);                         // Set per-channel master volume, (leftDb, rightDb) → Promise<void>
                                                                       // registers 0x20 and 0x21 independently
    await dac.setHeadphoneVolume(-10.0);                               // Set headphone volume, (leftDb, rightDb=leftDb) → Promise<void>
                                                                       // registers 0x22 / 0x23, below -96 dB mutes
    await dac.setSpeakerVolume(-10.0);                                 // Set speaker volume, (leftDb, rightDb=leftDb) → Promise<void>
                                                                       // registers 0x24 / 0x25
    await dac.setPcmVolume(0.0);                                       // Set PCM volume, (leftDb, rightDb=leftDb) → Promise<void>
                                                                       // 7-bit signed 0.5 dB, -51.5 to +12 dB
    await dac.mutePcm(false);                                          // Unmute PCM channels, (left, right=left) → Promise<void>
                                                                       // bit 7 of registers 0x1A / 0x1B
    await dac.setHeadphoneGain(GAIN.G_0_6047);                         // Select headphone analog gain, (index 0–7) → Promise<void>
                                                                       // HPGAIN bits 7:5 of register 0x0D
    console.log(await dac.headphoneGain());                            // Read headphone gain multiplier, () → Promise<number>
                                                                       // decodes HPGAIN
    await dac.muteOutputs(false, true);                                // Mute outputs, (headphone=null, speaker=null) → Promise<void>
                                                                       // PLAYBACK_CTL_2 mute bits
    await dac.setOutputs(OUT.ON, OUT.OFF);                             // Select amplifier power, (headphone, speaker) → Promise<void>
                                                                       // POWER_CTL_2 two bits per channel
    await dac.setSpeakerMode(false, false);                            // Configure the speaker channels, (mono, swap, mute5050=false) → Promise<void>
                                                                       // SPKMONO / SPKSWAP / MUTE50/50
    await dac.setClocking(clockingFor(12288000, 48000));               // Configure clocking from the datasheet table, ({auto, speed, group32k, videoClk, ratio, mclkDiv2}) → Promise<void>
                                                                       // CLOCKING_CTL register 0x05
    await dac.setInterface({ fmt: FMT.I2S });                          // Configure the serial port, ({master=false, fmt=FMT.I2S, wordLength=0, dsp=false, invertSclk=false, sclkEqMclk=false, invertSwitch=false}) → Promise<void>
                                                                       // registers 0x06 / 0x07
    await dac.setTone(3.0, 3.0);                                       // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → Promise<void>
                                                                       // 1.5 dB per step, TCEN enables
    await dac.setBeep(0xC, 2, 3, -6.0, BEEP.SINGLE);                   // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → Promise<void>
                                                                       // timing first, mode last
    await dac.stopBeep();                                              // Stop the beep generator, () → Promise<void>
                                                                       // BEEP[1:0] = 0
    await dac.setLimiter(true, { maxDb: -6, cushionDb: -12 });         // Configure the peak limiter, (enabled, {maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false}) → Promise<void>
                                                                       // registers 0x27 - 0x29
    await dac.setSoftRamp(true);                                       // Set soft ramp, (digital, analogLeft=false, analogRight=false) → Promise<void>
                                                                       // DIGSFT and ANLGSFTx
    await dac.setZeroCross(false);                                     // Set zero-cross, (digital, analogLeft=false, analogRight=false) → Promise<void>
                                                                       // DIGZC and ANLGZCx
    await dac.setDeemphasis(false);                                    // Set de-emphasis, (enabled) → Promise<void>
                                                                       // DEEMPH bit of MISC_CTL
    await dac.setPcmSwap(SRC.NORMAL, SRC.NORMAL);                      // Select PCM channel sources, (leftSource, rightSource) → Promise<void>
                                                                       // PCM_MIXER_SWAP
    await dac.invertPcm(false, false);                                 // Invert PCM polarity, (left, right) → Promise<void>
                                                                       // INV_PCMx
    await dac.setPassthrough(false);                                   // Route analog inputs to the outputs, (enabled, leftInput=1, rightInput=1) → Promise<void>
                                                                       // PASSTHRUx and PASSxSEL
    await dac.setPassthroughVolume(0.0);                               // Set passthrough volume, (leftDb, rightDb=leftDb) → Promise<void>
                                                                       // -60 to +12 dB
    await dac.mutePassthrough(true);                                   // Mute passthrough, (left, right=left) → Promise<void>
                                                                       // PASSxMUTE
    await dac.freeze(false);                                           // Release the register freeze, (on) → Promise<void>
                                                                       // FREEZE bit of MISC_CTL
    await dac.setBatteryCompensation(false);                           // Configure battery compensation, (enabled, vpRefIndex=0, monitor=false) → Promise<void>
                                                                       // BATTERY_COMP register
    console.log(await dac.readVpVoltage(1.8));                         // Read the VP supply voltage, (vaVolts) → Promise<number> V
                                                                       // VP_LEVEL * VA / 63.3
    console.log(await dac.readStatus());                               // Read status flags (clears them), () → Promise<object>
                                                                       // STATUS register is read-clear
    console.log(await dac.readSpeakerStatus());                        // Read speaker status, () → Promise<object>
                                                                       // short flags and SPKR/HP pin
    await dac.setChargePumpFrequency(5);                               // Set charge-pump frequency divider, (n 0–15) → Promise<void>
                                                                       // f = 64 * Fs / (n + 2)
    console.log(await dac.readRegister(0x02));                         // Read a raw register, (reg) → Promise<number>
                                                                       // documented registers only
    await dac.writeRegister(0x34, 0x5F);                               // Write a raw register, (reg, value) → Promise<void>
                                                                       // reserved and read-only registers are rejected
    await dac.stop();                                                  // Power down the DAC, () → Promise<void>
                                                                       // mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
}

main();
