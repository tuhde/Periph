///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CS43L22Full
import it.uhde.periph.chips.adc_dac.CS43L22Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, CS43L22Minimal.DEFAULT_ADDRESS).use { connection ->                              // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
        val dac = CS43L22Full(connection)                                                            // Create CS43L22 Full driver, (connection, resPin=null) → CS43L22Full
        dac.init()                                                                                   // Reset, verify ID, load defaults, () → Unit
                                                                                                     // reset pulse (if RESET pin), ID check, I2S slave defaults, required init block
        dac.start()                                                                                  // Power up the DAC once MCLK is running, () → Unit
                                                                                                     // writes POWER_CTL_1 = 0x9E
        dac.setVolume(-30.0)                                                                         // Set master volume, (db -102–12 dB) → Unit
                                                                                                     // code = round(db * 2) & 0xFF to registers 0x20 / 0x21
        println(dac.getVolume())                                                                     // Read back master volume, () → double dB
                                                                                                     // signed register 0x20 / 2
        println(dac.readId())                                                                        // Read chip ID and revision, () → int[] / Pair
                                                                                                     // register 0x01 split into bits 7:3 and 2:0
        dac.setVolumeChannels(-20.0, -24.0)                                                          // Set per-channel master volume, (leftDb, rightDb) → Unit
                                                                                                     // registers 0x20 and 0x21 independently
        dac.setHeadphoneVolume(-10.0)                                                                // Set headphone volume, (leftDb, rightDb=leftDb) → Unit
                                                                                                     // registers 0x22 / 0x23, below -96 dB mutes
        dac.setSpeakerVolume(-10.0)                                                                  // Set speaker volume, (leftDb, rightDb=leftDb) → Unit
                                                                                                     // registers 0x24 / 0x25
        dac.setPcmVolume(0.0)                                                                        // Set PCM volume, (leftDb, rightDb=leftDb) → Unit
                                                                                                     // 7-bit signed 0.5 dB, -51.5 to +12 dB
        dac.mutePcm(false, false)                                                                    // Unmute PCM channels, (left, right) → Unit
                                                                                                     // bit 7 of registers 0x1A / 0x1B
        dac.setHeadphoneGain(CS43L22Full.GAIN_0_6047)                                                // Select headphone analog gain, (index 0–7) → Unit
                                                                                                     // HPGAIN bits 7:5 of register 0x0D
        println(dac.headphoneGain())                                                                 // Read headphone gain multiplier, () → double
                                                                                                     // decodes HPGAIN
        dac.muteOutputs(false, true)                                                                 // Mute outputs, (headphone, speaker; null keeps) → Unit
                                                                                                     // PLAYBACK_CTL_2 mute bits
        dac.setOutputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF)                                      // Select amplifier power, (headphone, speaker) → Unit
                                                                                                     // POWER_CTL_2 two bits per channel
        dac.setSpeakerMode(false, false, false)                                                      // Configure the speaker channels, (mono, swap, mute5050) → Unit
                                                                                                     // SPKMONO / SPKSWAP / MUTE50/50
        val clk = CS43L22Full.clockingFor(12288000, 48000.0)                                         // Look up datasheet clocking, (mclkHz, fsHz) → Clocking
                                                                                                     // Table 1 of the datasheet
        dac.setClocking(clk)                                                                         // Configure clocking, (Clocking) → Unit
                                                                                                     // CLOCKING_CTL register 0x05
        dac.setInterface(false, CS43L22Full.FMT_I2S, 0, false, false, false, false)                  // Configure the serial port, (master, fmt, wordLength, dsp, invertSclk, sclkEqMclk, invertSwitch) → Unit
                                                                                                     // registers 0x06 / 0x07
        dac.setTone(3.0, 3.0, 0, 0, true)                                                            // Configure treble / bass shelves, (trebleDb, bassDb, trebleCorner, bassCorner, enabled) → Unit
                                                                                                     // 1.5 dB per step, TCEN enables
        dac.setBeep(0xC, 2, 3, -6.0, CS43L22Full.BEEP_SINGLE, true)                                  // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDb, mode, mix) → Unit
                                                                                                     // timing first, mode last
        dac.stopBeep()                                                                               // Stop the beep generator, () → Unit
                                                                                                     // BEEP[1:0] = 0
        dac.setLimiter(true, -6, -12)                                                                // Configure the peak limiter, (enabled, maxDb, cushionDb[, release, attack, limitAll, softRampDisable, zeroCrossDisable]) → Unit
                                                                                                     // registers 0x27 - 0x29
        dac.setSoftRamp(true, false, false)                                                          // Set soft ramp, (digital, analogLeft, analogRight) → Unit
                                                                                                     // DIGSFT and ANLGSFTx
        dac.setZeroCross(false, false, false)                                                        // Set zero-cross, (digital, analogLeft, analogRight) → Unit
                                                                                                     // DIGZC and ANLGZCx
        dac.setDeemphasis(false)                                                                     // Set de-emphasis, (enabled) → Unit
                                                                                                     // DEEMPH bit of MISC_CTL
        dac.setPcmSwap(CS43L22Full.SRC_NORMAL, CS43L22Full.SRC_NORMAL)                               // Select PCM channel sources, (leftSource, rightSource) → Unit
                                                                                                     // PCM_MIXER_SWAP
        dac.invertPcm(false, false)                                                                  // Invert PCM polarity, (left, right) → Unit
                                                                                                     // INV_PCMx
        dac.setPassthrough(false, 1, 1)                                                              // Route analog inputs to the outputs, (enabled, leftInput, rightInput) → Unit
                                                                                                     // PASSTHRUx and PASSxSEL
        dac.setPassthroughVolume(0.0, 0.0)                                                           // Set passthrough volume, (leftDb, rightDb) → Unit
                                                                                                     // -60 to +12 dB
        dac.mutePassthrough(true, true)                                                              // Mute passthrough, (left, right) → Unit
                                                                                                     // PASSxMUTE
        dac.freeze(false)                                                                            // Release the register freeze, (on) → Unit
                                                                                                     // FREEZE bit of MISC_CTL
        dac.setBatteryCompensation(false, 0, false)                                                  // Configure battery compensation, (enabled, vpRefIndex, monitor) → Unit
                                                                                                     // BATTERY_COMP register
        println(dac.readVpVoltage(1.8))                                                              // Read the VP supply voltage, (vaVolts) → double V
                                                                                                     // VP_LEVEL * VA / 63.3
        println(dac.readStatus().clockError)                                                         // Read status flags (clears them), () → Status
                                                                                                     // STATUS register is read-clear
        println(dac.readSpeakerStatus().spkrHpPin)                                                   // Read speaker status, () → SpeakerStatus
                                                                                                     // short flags and SPKR/HP pin
        dac.setChargePumpFrequency(5)                                                                // Set charge-pump frequency divider, (n 0–15) → Unit
                                                                                                     // f = 64 * Fs / (n + 2)
        println(dac.readRegister(0x02))                                                              // Read a raw register, (reg) → int
                                                                                                     // documented registers only
        dac.writeRegister(0x34, 0x5F)                                                                // Write a raw register, (reg, value) → Unit
                                                                                                     // reserved and read-only registers are rejected
        dac.stop()                                                                                   // Power down the DAC, () → Unit
                                                                                                     // mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
    }
}
