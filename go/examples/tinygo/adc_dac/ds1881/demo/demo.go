//go:build tinygo

// DS1881 demo example — TinyGo / Raspberry Pi Pico W.
//
// Stereo volume control with mute and optional EEPROM store.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const vdd float32 = 3.3

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

	// --- Fade the volume down and back up ---
	// Zero-crossing detection (on by default) moves the wiper only at a signal
	// zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
	// than the 50 ms maximum zero-crossing window.
	for step := 0; step < 41; step++ {
		db := step * 2
		if step > 20 {
			db = (40 - step) * 2
		}
		must(amp.SetAttenuationBoth(float32(db))) // Set both attenuations, (db0 dB, db1=db0 dB) → error
		p0, p1, _, err := amp.ReadAll()           // Read everything, () → (uint8, uint8, uint8, error)
		must(err)
		println(db, "dB -> positions", p0, p1)
		time.Sleep(100 * time.Millisecond)
	}

	// --- Mute and unmute one channel ---
	// Muting channel 0 leaves channel 1 playing; restoring writes the position again.
	must(amp.Mute(0))                   // Mute channel, (channel 0–1) → error
	muted, err := amp.GetAttenuation(0) // Read attenuation, (channel 0–1) → (float32 dB, error)
	must(err)
	println("channel 0 muted:", muted)
	time.Sleep(time.Second)
	must(amp.SetAttenuation(0, 0)) // Set attenuation, (channel 0–1, db dB) → error

	// --- Inspect the Option 2 attenuation table ---
	// Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
	// numeric value when the option changes, so re-set them afterwards.
	must(amp.SetOption(2)) // Select option, (option 1–2) → error
	for _, position := range []int{0, 12, 13, 24, 25, 32, 33} {
		println("position", position, "->", amp.PositionToDB(position), "dB") // Table lookup, (position) → float32 dB
	}
	must(amp.SetOption(1))           // Select option, (option 1–2) → error
	must(amp.SetAttenuationBoth(10)) // Set both attenuations, (db0 dB, db1=db0 dB) → error
	cfg, err := amp.GetConfig()      // Read configuration, () → (DS1881Config, error)
	must(err)
	println("option", cfg.Option, "zc", cfg.ZeroCrossing, "nv", cfg.Nonvolatile)

	// --- Optionally persist the volume ---
	// Stored positions are restored at power-up. The EEPROM is rated for only
	// 50 000 writes, so this runs only when requested with --store.
	if false { // no command line on the microcontroller; set to true to store
		println("WARNING: EEPROM endurance is 50 000 writes")
		must(amp.SetNonvolatile(true))  // Select EEPROM storage, (enabled bool) → error
		must(amp.Store())               // Store positions, () → error
		must(amp.SetNonvolatile(false)) // Select EEPROM storage, (enabled bool) → error
	}
}
