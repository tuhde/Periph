//go:build linux && !tinygo

// ES8311 complete example — Linux host.
//
// Opens /dev/i2c-N, constructs the driver (MCLK 12.288 MHz, 48 kHz) and exercises every method of the Full API.
// The audio stream itself runs over I2S, which this library does not provide.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x18"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x18) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewES8311Full(conn, 12288000, 48000) // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → (*ES8311Full, error)
	if err != nil {
		panic(err)
	}

	must(chip.SetVolume(-30)) // Set DAC volume, (db −95.5–32 dB) → error
	// writes 0x32 = round(db / 0.5) + 191
	v, err := chip.GetVolume() // Read back DAC volume, () → (float32 dB, error)
	// (0x32 − 191) × 0.5
	must(err)
	fmt.Printf("volume dB %.1f\n", v)
	must(chip.SetMute(false)) // Unmute the DAC, (muted=false) → error
	// clears DSM and DEM mute bits of 0x31
	must(chip.SetMicGain(27)) // Set microphone PGA gain, (db 0–30 dB) → error
	// 0x14 bits 3:0 = round(db / 3)
	g, err := chip.GetMicGain() // Read back PGA gain, () → (float32 dB, error)
	// 3 dB per register step
	must(err)
	fmt.Printf("mic gain dB %.1f\n", g)
	must(chip.ConfigureClock(12288000, 16000, false, false)) // Program the clock tree, (mclkHz Hz, sampleRateHz Hz, mclkFromBclk, mclkInvert) → error
	// preset table first, then computed pre-divider / multiplier / OSR
	must(chip.ConfigureSerial(adcdac.ES8311SerialConfig{Format: adcdac.ES8311FormatI2S, WordLength: 16})) // Configure the digital audio port, (ES8311SerialConfig{Format, WordLength=16, Master, BclkInvert, LrckInvert, DACRight, BclkDiv, LrckDiv, StoppedBclk}) → error
	// 0x09 / 0x0A format and word length, slave mode
	must(chip.SetTristate(false, false)) // Tri-state BCLK/LRCK and ADCDAT, (bclkLrck=false, adcdat=false) → error
	// 0x07 bits 5:4
	must(chip.SetInput(adcdac.ES8311InputAnalog)) // Select microphone input, (source ES8311InputAnalog|Digital|None) → error
	// MIC1P / MIC1N differential, PGA powered
	must(chip.ConfigureDMIC(false)) // Select PDM latch edge, (latchFallingEdge=false) → error
	// 0x15 bit 0
	must(chip.SetADCVolume(0)) // Set ADC digital volume, (db −95.5–32 dB) → error
	// 0x17; ALC maximum gain when the ALC is on
	must(chip.ConfigureADC(adcdac.DefaultES8311ADCConfig())) // Configure ADC path, (ES8311ADCConfig{ScaleDB=24 dB, OSR=64, DoubleSpeed=false, HPFOff=false, HPFCoeff1=0x0C, HPFCoeff2=0x0C}) → error
	// 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
	must(chip.SetADCRamp(0)) // Set ADC soft-ramp rate, (rate 0–15) → error
	// 0 = off; n = 0.25 dB per 2^(n+1) LRCK
	must(chip.ConfigureALC(adcdac.ES8311ALCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -18.1, Automute: true, NoiseGateDB: -60})) // Configure ALC, (ES8311ALCConfig{Enabled, MaxLevelDB, MinLevelDB, Window, Automute, AutomuteWindow, NoiseGateDB}) → error
	// 0x18–0x1A, levels rounded to the nearest listed value
	must(chip.ConfigureALC(adcdac.DefaultES8311ALCConfig())) // Disable ALC, (defaults) → error
	// restores defaults
	must(chip.BypassADCEq()) // Bypass ADC equalizer, () → error
	// 0x1C bit 6; SetADCEq loads five 30-bit coefficients b0,a1,a2,b1,b2
	must(chip.SetDACRamp(8)) // Set DAC soft-ramp rate, (rate 0–15) → error
	// 0x37 bits 7:4
	must(chip.ConfigureDRC(adcdac.ES8311DRCConfig{Enabled: true, MaxLevelDB: -12, MinLevelDB: -24.1})) // Configure DRC, (ES8311DRCConfig{Enabled, MaxLevelDB, MinLevelDB, Window}) → error
	// 0x34 / 0x35; DAC volume becomes the maximum gain
	must(chip.ConfigureDRC(adcdac.ES8311DRCConfig{MaxLevelDB: -30.1, MinLevelDB: -30.1})) // Disable DRC, (Enabled=false) → error
	// clears the DRC enable bit
	must(chip.BypassDACEq()) // Bypass DAC equalizer, () → error
	// 0x37 bit 3; SetDACEq loads three 30-bit coefficients b0,b1,a1
	must(chip.SetDACMuteOptions(true, true, false)) // Set DAC mute bits, (dsm=true, dem=true, dsmTarget=false) → error
	// 0x31 bits 7:5 individually
	must(chip.SetLoopback(true)) // Route ADC output into the DAC, (enabled) → error
	// 0x44 bit 7
	must(chip.SetLoopback(false)) // Disable loopback, (enabled=false) → error
	// 0x44 bit 7 cleared
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatDACADC)) // Select ADCDAT content, (mode ES8311Adcdat*) → error
	// DAC + ADC: digital feedback for echo cancellation
	must(chip.SetAdcdatMode(adcdac.ES8311AdcdatADCADC)) // Restore ADCDAT content, (mode ES8311AdcdatADCADC) → error
	// ADC data on both slots
	must(chip.SetSDPMute(false, false)) // Mute serial input / output, (input=false, output=false) → error
	// 0x09 / 0x0A bit 6
	must(chip.SetLowPower(adcdac.ES8311LowPower{})) // Clear all low-power bits, (ES8311LowPower{DAC, PGA, PGAOut, ADC, ADCRef, DACRef, Flash, Integrator}) → error
	// 0x0F; all off = best THD+N
	r, err := chip.ReadRegister(0x32) // Read raw register, (reg 0x00–0xFF) → (uint8, error)
	// returns the byte as stored in the chip
	must(err)
	fmt.Printf("reg 0x32 %d\n", r)
	must(chip.WriteRegister(0x32, 0xBF)) // Write raw register, (reg, value) → error
	// 0xBF = 0 dB DAC volume
	time.Sleep(1000 * time.Millisecond)
	must(chip.SetMute(true)) // Mute the DAC, (muted=true) → error
	// sets DSM and DEM mute
	must(chip.PowerDown()) // Enter standby, () → error
	// powers down analog blocks, resets digital, clears CSM_ON
	must(chip.PowerUp()) // Leave standby, () → error
	// re-enables analog blocks, clocks and CSM_ON
	must(chip.Reset()) // Software reset, () → error
	// registers return to reset values; reconfigure afterwards
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
