///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ES8311Full

def connection = new I2CConnection(1, 0x18)  // open I²C bus 1, device 0x18, (bus, address) → I2CConnection
try {
    def codec = new ES8311Full(connection, 12288000L, 48000L)  // construct driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → ES8311Full

    // --- Bring up the codec for a voice assistant ---
    // The control port only configures the codec; the I2S stream (MCLK, BCLK,
    // LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
    // speech recognition.
    codec.configureClock(12288000, 16000, false, false)                                         // Program the clock tree, (mclkHz Hz, sampleRateHz Hz, mclkFromBclk, mclkInvert) → void
    codec.configureSerial("i2s", 16)                                                            // Configure the digital audio port, (format="i2s", wordLength=16) → void

    // --- Configure the microphone path ---
    // 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
    // level; the noise gate and automute silence the line between utterances.
    codec.setMicGain(24.0)                                                                      // Set microphone PGA gain, (db 0–30 dB) → void
    println("mic gain dB " + codec.getMicGain())                                                // Read back PGA gain, () → double dB
    codec.setAdcVolume(0.0)                                                                     // Set ADC digital volume, (db −95.5–32 dB) → void
    codec.configureAlc(true, -12.0, -18.1, 0, true, 0, -60.0)                                   // Configure ALC, (enabled, maxLevelDb, minLevelDb, window, automute, automuteWindow, noiseGateDb) → void
    codec.configureAdc(24, 64, false, true, 0x0C, 0x0C)                                         // Configure ADC path, (scaleDb=24 dB, osr=64, doubleSpeed=false, hpf=true, hpfCoeff1, hpfCoeff2) → void

    // --- Configure playback ---
    // The DRC compresses loud peaks so a small speaker does not clip; the soft
    // ramp avoids clicks while the volume changes.
    codec.configureDrc(true, -12.0, -24.1, 0)                                                   // Configure DRC, (enabled, maxLevelDb, minLevelDb, window) → void
    codec.setDacRamp(8)                                                                         // Set DAC soft-ramp rate, (rate 0–15) → void
    codec.setMute(false)                                                                        // Unmute the DAC, (muted=false) → void

    // --- Ramp the playback volume ---
    // Step from -60 dB to -10 dB, reading the value back each time.
    for (int vol = -60; vol <= -10; vol += 10) {
        codec.setVolume(vol as double)                                                                    // Set DAC volume, (db −95.5–32 dB) → void
        println("volume dB " + codec.getVolume())                                               // Read back DAC volume, () → double dB
        Thread.sleep(500L)
    }

    // --- Echo-cancellation feedback and loopback ---
    // "dac_adc" puts the DAC data next to the microphone on ADCDAT so the host
    // can cancel the speaker echo; the loopback routes the microphone to the
    // speaker for a quick acoustic check.
    codec.setAdcdatMode("dac_adc")                                                              // Select ADCDAT content, (mode) → void
    Thread.sleep(2000L)
    codec.setAdcdatMode("adc_adc")                                                              // Restore ADCDAT content, (mode) → void
    codec.setLoopback(true)                                                                     // Route ADC output into the DAC, (enabled) → void
    Thread.sleep(2000L)
    codec.setLoopback(false)                                                                    // Disable loopback, (enabled=false) → void

    // --- Shut down ---
    // Mute first so power-down does not pop, then enter standby.
    codec.setMute(true)                                                                         // Mute the DAC, (muted=true) → void
    codec.powerDown()                                                                           // Enter standby, () → void
} finally {
    connection.close()
}
