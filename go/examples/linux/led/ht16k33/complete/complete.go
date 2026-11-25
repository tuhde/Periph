//go:build linux && !tinygo

// HT16K33 complete example — Linux host.
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

	matrix, err := led.NewHT16K33Full(conn) // Create HT16K33 driver, (connection) → (*HT16K33Full, error)
	if err != nil {
		panic(err)
	}

	must(matrix.Clear()) // Blank the display, () → error
	// zeros the shadow RAM and writes it
	must(matrix.SetPixel(2, 5, true)) // Set one LED, (com 0–7, row 0–15, on) → error
	must(matrix.Show())               // Write the shadow RAM, () → error
	lit, _ := matrix.GetPixel(2, 5)   // Read a pixel from the shadow RAM, (com, row) → (bool, error)
	fmt.Println(lit)
	must(matrix.WriteRaw([]byte{0xFF, 0x0F}, 0)) // Write raw RAM bytes, (data 1–16 bytes, address 0–15) → error
	// com 0 rows 0–11 lit
	must(matrix.SetBrightness(8)) // Set dimming, (level 0–15) → error
	// 9/16 duty
	must(matrix.SetBlink(1)) // Set blink frequency, (0, 0.5, 1, 2 Hz) → error
	// blinks at 1 Hz
	time.Sleep(3 * time.Second)
	must(matrix.SetBlink(0))
	must(matrix.DisplayOff())              // Display output off, () → error
	must(matrix.DisplayOn())               // Display output on, () → error
	must(matrix.SetIntOutput(true, false)) // ROW15/INT pin as INT output, (enable, activeHigh) → error
	// active low interrupt output
	keys, _ := matrix.ReadKeys() // Read and clear key data, () → (uint64 bitmask, error)
	fmt.Println(keys)
	pressed, _ := matrix.KeyPressed() // Read the INT flag, () → (bool, error)
	fmt.Println(pressed)
	polled, _ := matrix.PollInterrupt() // Read keys if INT flag set, () → (uint64, error) (0 if none)
	fmt.Println(polled)
	must(matrix.OnInterrupt(func(k uint64) { fmt.Println(k) })) // Subscribe to key presses, (callback) → error
	// polls every 20 ms when no INT pin is wired
	time.Sleep(100 * time.Millisecond)
	must(matrix.OffInterrupt()) // Unsubscribe, () → error
	must(matrix.Standby())      // Oscillator off, () → error
	must(matrix.Wake())         // Oscillator on and restore state, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
