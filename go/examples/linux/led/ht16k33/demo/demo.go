//go:build linux && !tinygo

// HT16K33 demo example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x70"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x70) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	// --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
	// A single lit pixel visits all 16 x 8 positions, the brightness is swept from
	// 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
	// for 20 s via the INT output before standby and wake.
	matrix, err := led.NewHT16K33Full(conn) // Create HT16K33 driver, (connection) → (*HT16K33Full, error)
	if err != nil {
		panic(err)
	}

	for com := uint8(0); com < led.HT16K33Commons; com++ {
		for row := uint8(0); row < led.HT16K33Rows; row++ {
			must(matrix.Clear())                  // Blank the display, () → error
			must(matrix.SetPixel(com, row, true)) // Set one LED, (com 0–7, row 0–15, on) → error
			must(matrix.Show())                   // Write the shadow RAM, () → error
			time.Sleep(50 * time.Millisecond)
		}
	}

	must(matrix.WriteRaw([]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
		0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, 0)) // All LEDs on, (data, address) → error
	for level := uint8(0); level < 16; level++ {
		must(matrix.SetBrightness(level)) // Sweep dimming, (level 0–15) → error
		time.Sleep(100 * time.Millisecond)
	}

	for _, f := range []float32{2, 1, 0.5, 0} {
		must(matrix.SetBlink(f)) // Set blink frequency, (0, 0.5, 1, 2 Hz) → error
		time.Sleep(3 * time.Second)
	}

	must(matrix.SetIntOutput(true, false)) // INT output, active low, (enable, activeHigh) → error
	for i := 0; i < 1000; i++ {            // 1000 x 20 ms = 20 s
		keys, err := matrix.PollInterrupt() // Read keys if pending, () → (uint64 bitmask, error)
		must(err)
		if keys != 0 {
			fmt.Printf("keys: 0x%x\n", keys)
		}
		time.Sleep(20 * time.Millisecond)
	}

	must(matrix.Standby()) // Oscillator off, () → error
	time.Sleep(time.Second)
	must(matrix.Wake()) // Oscillator on, state restored, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
