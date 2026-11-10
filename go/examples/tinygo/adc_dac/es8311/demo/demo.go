//go:build tinygo

// ES8311 demo example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 with GP4 = SDA and GP5 = SCL, constructs the driver (MCLK 12.288 MHz,
// 48 kHz) and brings up a voice-assistant codec, ramps the playback volume and shuts down.
// The audio stream itself runs over I2S, which this library does not provide.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x18, nil, nil) // Create I2C connection, (i2c, addr=0x18) → (*I2CConnection)
	chip, err := adcdac.NewES8311Full(conn, 12288000, 48000) // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → (*ES8311Full, error)
	if err != nil {
		panic(err)
	}

	// --- Bring up the codec for a voice assistant ---
	// The control port only configures the codec; the I2S stream (MCLK, BCLK,
	// LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
	// speech recognition.
	must(chip.ConfigureClock(12288000, 16000, false, false))                                              // Program the clock tree, (mclkHz Hz, sampleRateHz Hz, mclkFromBclk, mclkInvert) → error
	must(chip.ConfigureSerial(adcdac.ES8311SerialConfig{Format: adcdac.ES8311FormatI2S, WordLength: 16})) // Configure the digital audio port, (ES8311SerialConfig{Format, WordLength=16}) → error

	// --- Configure the microphone path ---
	// 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
	// level; the noise gate and automute silence the line between utterances.
	must(chip.SetMicGain(24))   // Set microphone PGA gain, (db 0–30 dB) → error
	g, err := chip.GetMicGain() // Read back PGA gain, () → (float32 dB, error)
	must(err)
	fmt.Printf("mic gain dB %.1f\n", g)
	must(chip.SetADCVolume(0))                                                                                                           // Set ADC digital volume, (db −95.5–32 dB) → error
	must(chip.ConfigureALC(adcdac.ES8311ALCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -18.1, Automute: true, NoiseGateDB: -60})) // Configure ALC, (ES8311ALCConfig{Enabled, MaxLevelDB, MinLevelDB, Automute, NoiseGateDB}) → error
	must(chip.ConfigureADC(adcdac.DefaultES8311ADCConfig()))                                                                             // Configure ADC path, (ES8311ADCConfig{ScaleDB=24 dB, OSR=64, HPF on}) → error

	// --- Configure playback ---
	// The DRC compresses loud peaks so a small speaker does not clip; the soft
	// ramp avoids clicks while the volume changes.
	must(chip.ConfigureDRC(adcdac.ES8311DRCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -24.1})) // Configure DRC, (ES8311DRCConfig{Enabled, MaxLevelDB, MinLevelDB, Window}) → error
	must(chip.SetDACRamp(8))                                                                           // Set DAC soft-ramp rate, (rate 0–15) → error
	must(chip.SetMute(false))                                                                          // Unmute the DAC, (muted=false) → error

	// --- Ramp the playback volume ---
	// Step from -60 dB to -10 dB, reading the value back each time.
	for vol := -60; vol <= -10; vol += 10 {
		must(chip.SetVolume(float32(vol))) // Set DAC volume, (db −95.5–32 dB) → error
		v, err := chip.GetVolume()         // Read back DAC volume, () → (float32 dB, error)
		must(err)
		fmt.Printf("volume dB %.1f\n", v)
		time.Sleep(500 * time.Millisecond)
	}

	// --- Echo-cancellation feedback and loopback ---
	// "DAC+ADC" puts the DAC data next to the microphone on ADCDAT so the host
	// can cancel the speaker echo; the loopback routes the microphone to the
	// speaker for a quick acoustic check.
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatDACADC)) // Select ADCDAT content, (mode ES8311Adcdat*) → error
	time.Sleep(2000 * time.Millisecond)
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatADCADC)) // Restore ADCDAT content, (mode ES8311AdcdatADCADC) → error
	must(chip.SetLoopback(true))                        // Route ADC output into the DAC, (enabled) → error
	time.Sleep(2000 * time.Millisecond)
	must(chip.SetLoopback(false)) // Disable loopback, (enabled=false) → error

	// --- Shut down ---
	// Mute first so power-down does not pop, then enter standby.
	must(chip.SetMute(true)) // Mute the DAC, (muted=true) → error
	must(chip.PowerDown())   // Enter standby, () → error

	for {
		time.Sleep(time.Second)
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
