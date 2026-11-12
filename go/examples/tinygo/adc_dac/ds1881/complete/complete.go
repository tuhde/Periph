//go:build tinygo

// DS1881 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the full driver.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.DS1881I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x2C) → *I2CConnection
	defer conn.Close()

	amp, err := adcdac.NewDS1881Full(conn) // Create DS1881 Full driver, (conn) → (*DS1881Full, error)
	must(err)
	must(amp.Init()) // Apply defaults, () → error
	// waits 1 ms, rewrites the config only if it differs

	must(amp.SetAttenuation(0, 12)) // Set attenuation, (channel 0–1, db dB) → error
	// nearest position, one command byte
	a, err := amp.GetAttenuation(0) // Read attenuation, (channel 0–1) → (float32 dB, error)
	// three-byte round-robin read, position in dB
	must(err)
	println(a)
	must(amp.SetPosition(1, 30)) // Set raw wiper position, (channel 0–1, position 0–63) → error
	// position 63 is mute in Option 1
	p, err := amp.GetPosition(1) // Read wiper position, (channel 0–1) → (uint8, error)
	// low six bits of the read-back byte
	must(err)
	println(p)
	must(amp.Mute(1)) // Mute channel, (channel 0–1) → error
	// writes the mute position (63)

	must(amp.SetBoth(5, 25)) // Set both wipers, (position0 0–63, position1 0–63) → error
	// one transaction, both wipers update together
	must(amp.SetAttenuationBoth(20)) // Set both attenuations, (db0 dB, db1=db0 dB) → error
	// db1 defaults to db0 (stereo volume)
	p0, p1, cfgByte, err := amp.ReadAll() // Read everything, () → (uint8, uint8, uint8, error)
	// one three-byte round-robin read
	must(err)
	println(p0, p1, cfgByte)

	must(amp.Configure(2, true, false)) // Configure, (option 1–2, zeroCrossing bool, nonvolatile bool) → error
	// writes the config byte only if it changed
	cfg, err := amp.GetConfig() // Read configuration, () → (DS1881Config, error)
	// fresh read; refreshes the driver's table
	must(err)
	println("option", cfg.Option, "zc", cfg.ZeroCrossing, "nv", cfg.Nonvolatile)
	println(amp.PositionToDB(13), amp.MaxPosition()) // Table lookup, (position) → float32 dB; Last non-mute position, () → uint8
	// Option 2: 13 → 14 dB, max position 32
	println(amp.DBToPosition(39)) // Nearest position, (db dB) → uint8
	// 70 dB and above map to mute
	must(amp.SetOption(1)) // Select option, (option 1–2) → error
	// positions are not converted
	must(amp.SetZeroCrossing(false)) // Set zero-crossing, (enabled bool) → error
	// ZC bit of the configuration
	must(amp.SetZeroCrossing(true)) // Set zero-crossing, (enabled bool) → error
	// clickless updates again
	must(amp.WaitReady(0.05)) // Acknowledge-poll, (timeoutS=0.05 s) → error
	// returns once the chip ACKs its address

	must(amp.SetNonvolatile(true)) // Select EEPROM storage, (enabled bool) → error
	// V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
	must(amp.Store()) // Store positions, () → error
	// rewrites both wipers and polls for completion
	must(amp.SetNonvolatile(false)) // Select EEPROM storage, (enabled bool) → error
	// back to volatile wipers
	time.Sleep(time.Second)
	must(amp.Reset()) // Reset, () → error
	// volatile, zero-crossing, Option 1, both wipers muted
}
