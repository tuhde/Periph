//go:build linux && !tinygo

// CS43L22 demo example — Linux host.
//
// Headphone amplifier bring-up with volume ramp, tone, limiter and beep.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.CS43L22I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x4A) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	// --- Bring up the headphone amplifier ---
	// The control port only configures the codec; MCLK = 12.288 MHz and the 48 kHz
	// I2S stream come from the board's I2S master. Init loads the I2S-slave
	// defaults while the chip is powered down; Start powers it up once MCLK runs.
	dac, err := adcdac.NewCS43L22Full(conn, nil) // Create CS43L22 Full driver, (conn, resPin=nil) → (*CS43L22Full, error)
	must(err)
	must(dac.Init())             // Reset, verify ID, load defaults, () → error
	id, rev, err := dac.ReadID() // Read chip ID and revision, () → (uint8, uint8, error)
	must(err)
	fmt.Println(id, rev)
	must(dac.SetVolume(-60)) // Set master volume, (db -102–12 dB) → error
	must(dac.Start())        // Power up the DAC once MCLK is running, () → error

	// --- Wait for the serial port to lock ---
	// SPCLKERR reads 1 until LRCK is valid and the MCLK/LRCK ratio is measured;
	// reading the status register clears the flags.
	for i := 0; i < 50; i++ {
		st, err := dac.ReadStatus() // Read status flags (clears them), () → (CS43L22Status, error)
		must(err)
		if !st.ClockError {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	// --- Ramp the volume up ---
	// 2 dB steps every 100 ms avoid an abrupt start; the digital soft ramp
	// smooths each step further.
	for vol := -60; vol <= 0; vol += 2 {
		must(dac.SetVolume(float32(vol))) // Set master volume, (db -102–12 dB) → error
		time.Sleep(100 * time.Millisecond)
	}

	// --- Shape the tone and protect the headphones ---
	// +6 dB bass and treble shelves add presence; the limiter at -6 dB stops
	// loud peaks from clipping the amplifier.
	must(dac.SetTone(6, 6, 0, 0, true))                                                                                  // Configure treble / bass shelves, (trebleDB, bassDB, trebleCorner, bassCorner, enabled) → error
	must(dac.SetLimiter(adcdac.CS43L22Limiter{Enabled: true, MaxDB: -6, CushionDB: -12, Release: 0x3F, LimitAll: true})) // Configure the peak limiter, (CS43L22Limiter) → error

	// --- Play a single beep ---
	// The beep generator needs no audio data; it mixes into the output once MCLK
	// and LRCK are running. Frequency code 0x1 is 521.74 Hz.
	must(dac.SetBeep(0x1, 2, 0, -6, adcdac.CS43L22BeepSingle, true)) // Play a beep, (frequency 0–15, onTime 0–15, offTime 0–7, volumeDB, mode, mix) → error
	time.Sleep(1500 * time.Millisecond)
	must(dac.StopBeep()) // Stop the beep generator, () → error

	// --- Power down safely ---
	// Stop mutes, disables ramps and waits 100 us after POWER_CTL_1 = 0x9F;
	// only then may the application remove MCLK.
	must(dac.Stop()) // Power down the DAC, () → error
	fmt.Println("MCLK may now be removed")
}
