///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ES8311Full

fun main() {
    I2CConnection(1, 0x18).use { connection ->  // open I²C bus 1, device 0x18, (bus, address) → I2CConnection
        val codec = ES8311Full(connection, 12288000L, 48000L)  // construct driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → ES8311Full
        codec.setVolume(-30.0)                                                                  // Set DAC volume, (db −95.5–32 dB) → Unit
                                                                                                // writes 0x32 = round(db / 0.5) + 191
        println("volume dB " + codec.getVolume())                                               // Read back DAC volume, () → double dB
                                                                                                // (0x32 − 191) × 0.5
        codec.setMute(false)                                                                    // Unmute the DAC, (muted=false) → Unit
                                                                                                // clears DSM and DEM mute bits of 0x31
        codec.setMicGain(27.0)                                                                  // Set microphone PGA gain, (db 0–30 dB) → Unit
                                                                                                // 0x14 bits 3:0 = round(db / 3)
        println("mic gain dB " + codec.getMicGain())                                            // Read back PGA gain, () → double dB
                                                                                                // 3 dB per register step
        codec.configureClock(12288000L, 16000L, false, false)                                   // Program the clock tree, (mclkHz Hz, sampleRateHz Hz, mclkFromBclk, mclkInvert) → Unit
                                                                                                // preset table first, then computed pre-divider / multiplier / OSR
        codec.configureSerial("i2s", 16, false, false, false, "left", null, null, true)         // Configure the digital audio port, (format, wordLength=16, master=false, bclkInvert=false, lrckInvert=false, dacChannel="left", bclkDiv=null, lrckDiv=null, continuousBclk=true) → Unit
                                                                                                // 0x09 / 0x0A format and word length, slave mode
        codec.setTristate(false, false)                                                         // Tri-state BCLK/LRCK and ADCDAT, (bclkLrck=false, adcdat=false) → Unit
                                                                                                // 0x07 bits 5:4
        codec.setInput("analog")                                                                // Select microphone input, (source "analog"|"digital"|"none") → Unit
                                                                                                // MIC1P / MIC1N differential, PGA powered
        codec.configureDmic(false)                                                              // Select PDM latch edge, (latchFallingEdge=false) → Unit
                                                                                                // 0x15 bit 0
        codec.setAdcVolume(0.0)                                                                 // Set ADC digital volume, (db −95.5–32 dB) → Unit
                                                                                                // 0x17; ALC maximum gain when the ALC is on
        codec.configureAdc(24, 64, false, true, 0x0C, 0x0C)                                     // Configure ADC path, (scaleDb=24 dB, osr=64, doubleSpeed=false, hpf=true, hpfCoeff1=0x0C, hpfCoeff2=0x0C) → Unit
                                                                                                // 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
        codec.setAdcRamp(0)                                                                     // Set ADC soft-ramp rate, (rate 0–15) → Unit
                                                                                                // 0 = off; n = 0.25 dB per 2^(n+1) LRCK
        codec.configureAlc(true, -12.0, -18.1, 0, true, 0, -60.0)                               // Configure ALC, (enabled, maxLevelDb, minLevelDb, window, automute, automuteWindow, noiseGateDb) → Unit
                                                                                                // 0x18–0x1A, levels rounded to the nearest listed value
        codec.configureAlc(false)                                                               // Disable ALC, (enabled=false) → Unit
                                                                                                // restores defaults
        codec.bypassAdcEq()                                                                     // Bypass ADC equalizer, () → Unit
                                                                                                // 0x1C bit 6; setAdcEq loads five 30-bit coefficients b0,a1,a2,b1,b2
        codec.setDacRamp(8)                                                                     // Set DAC soft-ramp rate, (rate 0–15) → Unit
                                                                                                // 0x37 bits 7:4
        codec.configureDrc(true, -12.0, -24.1, 0)                                               // Configure DRC, (enabled, maxLevelDb, minLevelDb, window) → Unit
                                                                                                // 0x34 / 0x35; DAC volume becomes the maximum gain
        codec.configureDrc(false)                                                               // Disable DRC, (enabled=false) → Unit
                                                                                                // clears the DRC enable bit
        codec.bypassDacEq()                                                                     // Bypass DAC equalizer, () → Unit
                                                                                                // 0x37 bit 3; setDacEq loads three 30-bit coefficients b0,b1,a1
        codec.setDacMuteOptions(true, true, false)                                              // Set DAC mute bits, (dsm=true, dem=true, dsmTarget=false) → Unit
                                                                                                // 0x31 bits 7:5 individually
        codec.setLoopback(true)                                                                 // Route ADC output into the DAC, (enabled) → Unit
                                                                                                // 0x44 bit 7
        codec.setLoopback(false)                                                                // Disable loopback, (enabled=false) → Unit
                                                                                                // 0x44 bit 7 cleared
        codec.setAdcdatMode("dac_adc")                                                          // Select ADCDAT content, (mode "adc_adc"|"adc_zero"|"zero_adc"|"dac_adc"|"adc_dac"|"dac_dac") → Unit
                                                                                                // DAC + ADC: digital feedback for echo cancellation
        codec.setAdcdatMode("adc_adc")                                                          // Restore ADCDAT content, (mode="adc_adc") → Unit
                                                                                                // ADC data on both slots
        codec.setSdpMute(false, false)                                                          // Mute serial input / output, (input=false, output=false) → Unit
                                                                                                // 0x09 / 0x0A bit 6
        codec.setLowPower(false, false, false, false, false, false, false, false)               // Set low-power bits, (dac, pga, pgaOut, adc, adcRef, dacRef, flash, integrator) → Unit
                                                                                                // 0x0F; all off = best THD+N
        println("reg 0x32 " + codec.readRegister(0x32))                                         // Read raw register, (register 0x00–0xFF) → int
                                                                                                // returns the byte as stored in the chip
        codec.writeRegister(0x32, 0xBF)                                                         // Write raw register, (register, value) → Unit
                                                                                                // 0xBF = 0 dB DAC volume
        Thread.sleep(1000L)
        codec.setMute(true)                                                                     // Mute the DAC, (muted=true) → Unit
                                                                                                // sets DSM and DEM mute
        codec.powerDown()                                                                       // Enter standby, () → Unit
                                                                                                // powers down analog blocks, resets digital, clears CSM_ON
        codec.powerUp()                                                                         // Leave standby, () → Unit
                                                                                                // re-enables analog blocks, clocks and CSM_ON
        codec.reset()                                                                           // Software reset, () → Unit
                                                                                                // registers return to reset values; reconfigure afterwards
    }
}
