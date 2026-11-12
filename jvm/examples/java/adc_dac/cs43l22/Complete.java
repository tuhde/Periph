///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.CS43L22Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, CS43L22Full.DEFAULT_ADDRESS)) {                 // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
            var dac = new CS43L22Full(connection);                                                   // Create CS43L22 Full driver, (connection, resPin=null) → CS43L22Full
            dac.init();                                                                              // Reset, verify ID, load defaults, () → void
                                                                                                     // reset pulse (if RESET pin), ID check, I2S slave defaults, required init block
            dac.start();                                                                             // Power up the DAC once MCLK is running, () → void
                                                                                                     // writes POWER_CTL_1 = 0x9E
            dac.setVolume(-30.0);                                                                    // Set master volume, (db -102–12 dB) → void
                                                                                                     // code = round(db * 2) & 0xFF to registers 0x20 / 0x21
            System.out.println(dac.getVolume());                                                     // Read back master volume, () → double dB
                                                                                                     // signed register 0x20 / 2
            System.out.println(dac.readId()[0] + " " + dac.readId()[1]);                             // Read chip ID and revision, () → int[] / Pair
                                                                                                     // register 0x01 split into bits 7:3 and 2:0
            dac.setVolumeChannels(-20.0, -24.0);                                                     // Set per-channel master volume, (leftDb, rightDb) → void
                                                                                                     // registers 0x20 and 0x21 independently
            dac.setHeadphoneVolume(-10.0);                                                           // Set headphone volume, (leftDb, rightDb=leftDb) → void
                                                                                                     // registers 0x22 / 0x23, below -96 dB mutes
            dac.setSpeakerVolume(-10.0);                                                             // Set speaker volume, (leftDb, rightDb=leftDb) → void
                                                                                                     // registers 0x24 / 0x25
            dac.setPcmVolume(0.0);                                                                   // Set PCM volume, (leftDb, rightDb=leftDb) → void
                                                                                                     // 7-bit signed 0.5 dB, -51.5 to +12 dB
            dac.mutePcm(false, false);                                                               // Unmute PCM channels, (left, right) → void
                                                                                                     // bit 7 of registers 0x1A / 0x1B
            dac.setHeadphoneGain(CS43L22Full.GAIN_0_6047);                                           // Select headphone analog gain, (index 0–7) → void
                                                                                                     // HPGAIN bits 7:5 of register 0x0D
            System.out.println(dac.headphoneGain());                                                 // Read headphone gain multiplier, () → double
                                                                                                     // decodes HPGAIN
            dac.muteOutputs(false, true);                                                            // Mute outputs, (headphone, speaker; null keeps) → void
                                                                                                     // PLAYBACK_CTL_2 mute bits
            dac.setOutputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF);                                 // Select amplifier power, (headphone, speaker) → void
                                                                                                     // POWER_CTL_2 two bits per channel
            dac.setSpeakerMode(false, false, false);                                                 // Configure the speaker channels, (mono, swap, mute5050) → void
                                                                                                     // SPKMONO / SPKSWAP / MUTE50/50
            var clk = CS43L22Full.clockingFor(12288000, 48000.0);                                    // Look up datasheet clocking, (mclkHz, fsHz) → Clocking
                                                                                                     // Table 1 of the datasheet
            dac.setClocking(clk);                                                                    // Configure clocking, (Clocking) → void
                                                                                                     // CLOCKING_CTL register 0x05
            dac.setInterface(false, CS43L22Full.FMT_I2S, 0, false, false, false, false);             // Configure the serial port, (master, fmt, wordLength, dsp, invertSclk, sclkEqMclk, invertSwitch) → void
                                                                                                     // registers 0x06 / 0x07
            dac.setTone(3.0, 3.0, 0, 0, true);                                                       // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner, bassCorner, enabled) → void
                                                                                                     // 1.5 dB per step, TCEN enables
            dac.setBeep(0xC, 2, 3, -6.0, CS43L22Full.BEEP_SINGLE, true);                             // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix) → void
                                                                                                     // timing first, mode last
            dac.stopBeep();                                                                          // Stop the beep generator, () → void
                                                                                                     // BEEP[1:0] = 0
            dac.setLimiter(true, -6, -12);                                                           // Configure the peak limiter, (enabled, maxDb, cushionDb[, release, attack, limitAll, softRampDisable, zeroCrossDisable]) → void
                                                                                                     // registers 0x27 - 0x29
            dac.setSoftRamp(true, false, false);                                                     // Set soft ramp, (digital, analogLeft, analogRight) → void
                                                                                                     // DIGSFT and ANLGSFTx
            dac.setZeroCross(false, false, false);                                                   // Set zero-cross, (digital, analogLeft, analogRight) → void
                                                                                                     // DIGZC and ANLGZCx
            dac.setDeemphasis(false);                                                                // Set de-emphasis, (enabled) → void
                                                                                                     // DEEMPH bit of MISC_CTL
            dac.setPcmSwap(CS43L22Full.SRC_NORMAL, CS43L22Full.SRC_NORMAL);                          // Select PCM channel sources, (leftSource, rightSource) → void
                                                                                                     // PCM_MIXER_SWAP
            dac.invertPcm(false, false);                                                             // Invert PCM polarity, (left, right) → void
                                                                                                     // INV_PCMx
            dac.setPassthrough(false, 1, 1);                                                         // Route analog inputs to the outputs, (enabled, leftInput, rightInput) → void
                                                                                                     // PASSTHRUx and PASSxSEL
            dac.setPassthroughVolume(0.0, 0.0);                                                      // Set passthrough volume, (leftDb, rightDb) → void
                                                                                                     // -60 to +12 dB
            dac.mutePassthrough(true, true);                                                         // Mute passthrough, (left, right) → void
                                                                                                     // PASSxMUTE
            dac.freeze(false);                                                                       // Release the register freeze, (on) → void
                                                                                                     // FREEZE bit of MISC_CTL
            dac.setBatteryCompensation(false, 0, false);                                             // Configure battery compensation, (enabled, vpRefIndex, monitor) → void
                                                                                                     // BATTERY_COMP register
            System.out.println(dac.readVpVoltage(1.8));                                              // Read the VP supply voltage, (vaVolts) → double V
                                                                                                     // VP_LEVEL * VA / 63.3
            System.out.println(dac.readStatus().clockError);                                         // Read status flags (clears them), () → Status
                                                                                                     // STATUS register is read-clear
            System.out.println(dac.readSpeakerStatus().spkrHpPin);                                   // Read speaker status, () → SpeakerStatus
                                                                                                     // short flags and SPKR/HP pin
            dac.setChargePumpFrequency(5);                                                           // Set charge-pump frequency divider, (n 0–15) → void
                                                                                                     // f = 64 * Fs / (n + 2)
            System.out.println(dac.readRegister(0x02));                                              // Read a raw register, (reg) → int
                                                                                                     // documented registers only
            dac.writeRegister(0x34, 0x5F);                                                           // Write a raw register, (reg, value) → void
                                                                                                     // reserved and read-only registers are rejected
            dac.stop();                                                                              // Power down the DAC, () → void
                                                                                                     // mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
        }
    }
}
