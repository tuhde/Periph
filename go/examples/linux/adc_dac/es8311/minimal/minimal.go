//go:build linux && !tinygo

// ES8311 minimal example — Linux host.
//
// Opens /dev/i2c-N, constructs the driver (MCLK 12.288 MHz, 48 kHz) and sets microphone gain, unmutes and sets the playback volume, then mutes again.
// The audio stream itself runs over I2S, which this library does not provide.
package main

import (
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

	chip, err := adcdac.NewES8311Minimal(conn, 12288000, 48000) // Create ES8311 driver and run the init sequence, (connection, mclkHz=12288000 Hz, sampleRateHz=48000 Hz) → (*ES8311Minimal, error)
	if err != nil {
		panic(err)
	}

	must(chip.SetMicGain(24)) // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → error
	must(chip.SetMute(false)) // Unmute the DAC, (muted=false) → error
	must(chip.SetVolume(-20)) // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → error
	time.Sleep(5000 * time.Millisecond)
	must(chip.SetMute(true)) // Mute the DAC, (muted=true) → error
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
