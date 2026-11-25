//go:build tinygo

// CS43L22 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of CS43L22Full.
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 100 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.CS43L22I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x4A) → *I2CConnection
	defer conn.Close()

	dac, err := adcdac.NewCS43L22Full(conn, nil) // Create CS43L22 Full driver, (conn, resPin=nil) → (*CS43L22Full, error)
	must(err)
	must(dac.Init()) // Reset, verify ID, load defaults, () → error
	// reset pulse (if RESET pin), ID check, I2S slave defaults, required init block
	must(dac.Start()) // Power up the DAC once MCLK is running, () → error
	// writes POWER_CTL_1 = 0x9E
	must(dac.SetVolume(-30)) // Set master volume, (db -102–12 dB) → error
	// code = round(db * 2) & 0xFF to registers 0x20 / 0x21
	vol, err := dac.GetVolume() // Read back master volume, () → (float32 dB, error)
	// signed register 0x20 / 2
	must(err)
	println(vol)
	id, rev, err := dac.ReadID() // Read chip ID and revision, () → (uint8, uint8, error)
	// register 0x01 split into bits 7:3 and 2:0
	must(err)
	println(id, rev)
	must(dac.SetVolumeChannels(-20, -24)) // Set per-channel master volume, (leftDB, rightDB) → error
	// registers 0x20 and 0x21 independently
	must(dac.SetHeadphoneVolume(-10, -10)) // Set headphone volume, (leftDB, rightDB) → error
	// registers 0x22 / 0x23, below -96 dB mutes
	must(dac.SetSpeakerVolume(-10, -10)) // Set speaker volume, (leftDB, rightDB) → error
	// registers 0x24 / 0x25
	must(dac.SetPCMVolume(0, 0)) // Set PCM volume, (leftDB, rightDB) → error
	// 7-bit signed 0.5 dB, -51.5 to +12 dB
	must(dac.MutePCM(false, false)) // Unmute PCM channels, (left, right) → error
	// bit 7 of registers 0x1A / 0x1B
	must(dac.SetHeadphoneGain(adcdac.CS43L22Gain06047)) // Select headphone analog gain, (index 0–7) → error
	// HPGAIN bits 7:5 of register 0x0D
	gain, err := dac.HeadphoneGain() // Read headphone gain multiplier, () → (float32, error)
	// decodes HPGAIN
	must(err)
	println(gain)
	must(dac.MuteOutputs(0, 1)) // Mute outputs, (headphone, speaker; 1 mute, 0 unmute, -1 keep) → error
	// PLAYBACK_CTL_2 mute bits
	must(dac.SetOutputs(adcdac.CS43L22OutOn, adcdac.CS43L22OutOff)) // Select amplifier power, (headphone, speaker) → error
	// POWER_CTL_2 two bits per channel
	must(dac.SetSpeakerMode(false, false, false)) // Configure the speaker channels, (mono, swap, mute5050) → error
	// SPKMONO / SPKSWAP / MUTE50/50
	clk, err := adcdac.CS43L22ClockingFor(12288000, 48000) // Look up datasheet clocking, (mclkHz, fsHz) → (CS43L22Clocking, error)
	// Table 1 of the datasheet
	must(err)
	must(dac.SetClocking(clk)) // Configure clocking, (CS43L22Clocking) → error
	// CLOCKING_CTL register 0x05
	must(dac.SetInterface(adcdac.DefaultCS43L22Interface())) // Configure the serial port, (CS43L22Interface) → error
	// registers 0x06 / 0x07
	must(dac.SetTone(3, 3, 0, 0, true)) // Configure treble / bass shelves, (trebleDB, bassDB, trebleCorner, bassCorner, enabled) → error
	// 1.5 dB per step, TCEN enables
	must(dac.SetBeep(0xC, 2, 3, -6, adcdac.CS43L22BeepSingle, true)) // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDB, mode, mix) → error
	// timing first, mode last
	must(dac.StopBeep()) // Stop the beep generator, () → error
	// BEEP[1:0] = 0
	must(dac.SetLimiter(adcdac.CS43L22Limiter{Enabled: true, MaxDB: -6, CushionDB: -12, Release: 0x3F, LimitAll: true})) // Configure the peak limiter, (CS43L22Limiter) → error
	// registers 0x27 - 0x29
	must(dac.SetSoftRamp(true, false, false)) // Set soft ramp, (digital, analogLeft, analogRight) → error
	// DIGSFT and ANLGSFTx
	must(dac.SetZeroCross(false, false, false)) // Set zero-cross, (digital, analogLeft, analogRight) → error
	// DIGZC and ANLGZCx
	must(dac.SetDeemphasis(false)) // Set de-emphasis, (enabled) → error
	// DEEMPH bit of MISC_CTL
	must(dac.SetPCMSwap(adcdac.CS43L22SrcNormal, adcdac.CS43L22SrcNormal)) // Select PCM channel sources, (leftSource, rightSource) → error
	// PCM_MIXER_SWAP
	must(dac.InvertPCM(false, false)) // Invert PCM polarity, (left, right) → error
	// INV_PCMx
	must(dac.SetPassthrough(false, 1, 1)) // Route analog inputs to the outputs, (enabled, leftInput, rightInput) → error
	// PASSTHRUx and PASSxSEL
	must(dac.SetPassthroughVolume(0, 0)) // Set passthrough volume, (leftDB, rightDB) → error
	// -60 to +12 dB
	must(dac.MutePassthrough(true, true)) // Mute passthrough, (left, right) → error
	// PASSxMUTE
	must(dac.Freeze(false)) // Release the register freeze, (on) → error
	// FREEZE bit of MISC_CTL
	must(dac.SetBatteryCompensation(false, 0, false)) // Configure battery compensation, (enabled, vpRefIndex, monitor) → error
	// BATTERY_COMP register
	vp, err := dac.ReadVPVoltage(1.8) // Read the VP supply voltage, (vaVolts) → (float32 V, error)
	// VP_LEVEL * VA / 63.3
	must(err)
	println(vp)
	status, err := dac.ReadStatus() // Read status flags (clears them), () → (CS43L22Status, error)
	// STATUS register is read-clear
	must(err)
	println(status.ClockError)
	speaker, err := dac.ReadSpeakerStatus() // Read speaker status, () → (CS43L22SpeakerStatus, error)
	// short flags and SPKR/HP pin
	must(err)
	println(speaker.SpkrHpPin)
	must(dac.SetChargePumpFrequency(5)) // Set charge-pump frequency divider, (n 0–15) → error
	// f = 64 * Fs / (n + 2)
	raw, err := dac.ReadRegister(0x02) // Read a raw register, (reg) → (uint8, error)
	// documented registers only
	must(err)
	println(raw)
	must(dac.WriteRegister(0x34, 0x5F)) // Write a raw register, (reg, value) → error
	// reserved and read-only registers are rejected
	must(dac.Stop()) // Power down the DAC, () → error
	// mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us

}
