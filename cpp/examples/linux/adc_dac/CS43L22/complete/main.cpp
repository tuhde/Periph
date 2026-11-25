#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CS43L22.h"

int main() {
    I2CConnectionLinux connection(1, CS43L22Full::I2C_ADDRESS);
    CS43L22Full dac(connection);                     // Create CS43L22 Full driver, (connection, resPin=nullptr)
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
                                                     // reset pulse (if RESET pin), ID check, I2S slave defaults, required init block
    dac.start();                                     // Power up the DAC once MCLK is running, () → void
                                                     // writes POWER_CTL_1 = 0x9E
    dac.setVolume(-30.0f);                           // Set master volume, (db -102–12 dB) → void
                                                     // code = round(db * 2) & 0xFF to registers 0x20 / 0x21
    float v = dac.getVolume();                       // Read back master volume, () → float dB
                                                     // signed register 0x20 / 2
    uint8_t id, rev;
    dac.readId(id, rev);                             // Read chip ID and revision, (&chipId, &revision) → void
                                                     // register 0x01 split into bits 7:3 and 2:0
    dac.setVolumeChannels(-20.0f, -24.0f);           // Set per-channel master volume, (leftDb, rightDb) → void
                                                     // registers 0x20 and 0x21 independently
    dac.setHeadphoneVolume(-10.0f, -10.0f);          // Set headphone volume, (leftDb, rightDb) → void
                                                     // registers 0x22 / 0x23, below -96 dB mutes
    dac.setSpeakerVolume(-10.0f, -10.0f);            // Set speaker volume, (leftDb, rightDb) → void
                                                     // registers 0x24 / 0x25
    dac.setPcmVolume(0.0f, 0.0f);                    // Set PCM volume, (leftDb, rightDb) → void
                                                     // 7-bit signed 0.5 dB, -51.5 to +12 dB
    dac.mutePcm(false, false);                       // Unmute PCM channels, (left, right) → void
                                                     // bit 7 of registers 0x1A / 0x1B
    dac.setHeadphoneGain(CS43L22Full::GAIN_0_6047);  // Select headphone analog gain, (index 0–7) → bool
                                                     // HPGAIN bits 7:5 of register 0x0D
    float g = dac.headphoneGain();                   // Read headphone gain multiplier, () → float
                                                     // decodes HPGAIN
    dac.muteOutputs(0, 1);                           // Mute outputs, (headphone, speaker; 1 mute, 0 unmute, -1 keep) → void
                                                     // PLAYBACK_CTL_2 mute bits
    dac.setOutputs(CS43L22Full::OUT_ON, CS43L22Full::OUT_OFF); // Select amplifier power, (headphone, speaker) → bool
                                                     // POWER_CTL_2 two bits per channel
    dac.setSpeakerMode(false, false);                // Configure the speaker channels, (mono, swap, mute5050=false) → void
                                                     // SPKMONO / SPKSWAP / MUTE50/50
    CS43L22Clocking clk;
    CS43L22Full::clockingFor(12288000, 48000.0f, clk); // Look up datasheet clocking, (mclkHz, fsHz, &out) → bool
                                                     // Table 1 of the datasheet
    dac.setClocking(clk.autoDetect, clk.speed, clk.group32k, clk.videoClk, clk.ratio, clk.mclkDiv2); // Configure clocking, (autoDetect, speed, group32k, videoClk, ratio, mclkDiv2) → bool
                                                     // CLOCKING_CTL register 0x05
    dac.setInterface(false, CS43L22Full::FMT_I2S, 0); // Configure the serial port, (master=false, fmt=FMT_I2S, wordLength=0, dsp=false, invertSclk=false, sclkEqMclk=false, invertSwitch=false) → bool
                                                     // registers 0x06 / 0x07
    dac.setTone(3.0f, 3.0f);                         // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner=0, bassCorner=0, enabled=true) → bool
                                                     // 1.5 dB per step, TCEN enables
    dac.setBeep(0xC, 2, 3, -6.0f, CS43L22Full::BEEP_SINGLE); // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix=true) → bool
                                                     // timing first, mode last
    dac.stopBeep();                                  // Stop the beep generator, () → void
                                                     // BEEP[1:0] = 0
    dac.setLimiter(true, -6, -12);                   // Configure the peak limiter, (enabled, maxDb=0, cushionDb=0, release=0x3F, attack=0, limitAll=true, softRampDisable=false, zeroCrossDisable=false) → bool
                                                     // registers 0x27 - 0x29
    dac.setSoftRamp(true);                           // Set soft ramp, (digital, analogLeft=false, analogRight=false) → void
                                                     // DIGSFT and ANLGSFTx
    dac.setZeroCross(false);                         // Set zero-cross, (digital, analogLeft=false, analogRight=false) → void
                                                     // DIGZC and ANLGZCx
    dac.setDeemphasis(false);                        // Set de-emphasis, (enabled) → void
                                                     // DEEMPH bit of MISC_CTL
    dac.setPcmSwap(CS43L22Full::SRC_NORMAL, CS43L22Full::SRC_NORMAL); // Select PCM channel sources, (leftSource, rightSource) → bool
                                                     // PCM_MIXER_SWAP
    dac.invertPcm(false, false);                     // Invert PCM polarity, (left, right) → void
                                                     // INV_PCMx
    dac.setPassthrough(false);                       // Route analog inputs to the outputs, (enabled, leftInput=1, rightInput=1) → bool
                                                     // PASSTHRUx and PASSxSEL
    dac.setPassthroughVolume(0.0f, 0.0f);            // Set passthrough volume, (leftDb, rightDb) → void
                                                     // -60 to +12 dB
    dac.mutePassthrough(true, true);                 // Mute passthrough, (left, right) → void
                                                     // PASSxMUTE
    dac.freeze(false);                               // Release the register freeze, (on) → void
                                                     // FREEZE bit of MISC_CTL
    dac.setBatteryCompensation(false);               // Configure battery compensation, (enabled, vpRefIndex=0, monitor=false) → bool
                                                     // BATTERY_COMP register
    float vp = dac.readVpVoltage(1.8f);              // Read the VP supply voltage, (vaVolts) → float V
                                                     // VP_LEVEL * VA / 63.3
    CS43L22Status st = dac.readStatus();             // Read status flags (clears them), () → CS43L22Status
                                                     // STATUS register is read-clear
    CS43L22SpeakerStatus sp = dac.readSpeakerStatus(); // Read speaker status, () → CS43L22SpeakerStatus
                                                     // short flags and SPKR/HP pin
    dac.setChargePumpFrequency(5);                   // Set charge-pump frequency divider, (n 0–15) → bool
                                                     // f = 64 * Fs / (n + 2)
    uint8_t raw;
    dac.readRegister(0x02, raw);                     // Read a raw register, (reg, &value) → bool
                                                     // documented registers only
    dac.writeRegister(0x34, 0x5F);                   // Write a raw register, (reg, value) → bool
                                                     // reserved and read-only registers are rejected
    dac.stop();                                      // Power down the DAC, () → void
                                                     // mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
    (void)v; (void)g; (void)vp; (void)st; (void)sp; (void)raw; (void)id; (void)rev;
    return 0;
}
