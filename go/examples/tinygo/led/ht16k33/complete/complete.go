//go:build tinygo

// HT16K33 complete example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
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
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x70, nil, nil) // Create I2C connection, (i2c, addr=0x70) → (*I2CConnection)

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
