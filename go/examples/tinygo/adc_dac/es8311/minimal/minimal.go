//go:build tinygo

// ES8311 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 with GP4 = SDA and GP5 = SCL, constructs the driver (MCLK 12.288 MHz,
// 48 kHz) and sets microphone gain, unmutes and sets the playback volume, then mutes again.
// The audio stream itself runs over I2S, which this library does not provide.
package main

import (
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

	conn := connection.NewI2CConnection(i2c, 0x18, nil, nil)    // Create I2C connection, (i2c, addr=0x18) → (*I2CConnection)
	chip, err := adcdac.NewES8311Minimal(conn, 12288000, 48000) // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → (*ES8311Minimal, error)
	if err != nil {
		panic(err)
	}

	must(chip.SetMicGain(24)) // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → error
	must(chip.SetMute(false)) // Unmute the DAC, (muted=false) → error
	must(chip.SetVolume(-20)) // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → error
	time.Sleep(5000 * time.Millisecond)
	must(chip.SetMute(true)) // Mute the DAC, (muted=true) → error

	for {
		time.Sleep(time.Second)
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
