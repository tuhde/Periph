//go:build tinygo

// SAA1064 demo — TinyGo / Raspberry Pi Pico W.
//
// Four-digit counter, decimal point sweep, current steps, static mode and segment test. Configures machine.I2C1 with GP4 = SDA and GP5 = SCL.
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
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)

	display, err := led.NewSAA1064Full(conn) // Create SAA1064 driver, (connection) → (*SAA1064Full, error)
	must(err)

	// --- Four-digit counter ---
	// A fast counter from 0 to 9999, the decimal point sweeping across the four
	// positions, the segment current stepped through 3/9/15/21 mA, a switch to
	// static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
	// status byte (the PR flag is set after power-up and clears on the first read).
	for n := 0; n < 10000; n += 37 {
		must(display.ShowNumber(n)) // Show the counter, (value 0–9999) → error
		time.Sleep(10 * time.Millisecond)
	}

	must(display.ShowDigits([4]int{1, 2, 3, 4})) // Show four digits, (digits) → error
	for pos := uint8(1); pos <= 4; pos++ {
		must(display.SetDecimalPoint(pos, true)) // Light a decimal point, (position, on) → error
		time.Sleep(400 * time.Millisecond)
		must(display.SetDecimalPoint(pos, false))
	}

	for _, ma := range []int{3, 9, 15, 21} {
		must(display.SetCurrent(ma)) // Step the segment current, (ma) → error
		must(display.ShowNumber(8888))
		time.Sleep(time.Second)
	}
	must(display.SetCurrent(12))

	must(display.SetMode(false)) // Static mode, (dynamic) → error
	time.Sleep(2 * time.Second)
	must(display.SetMode(true))

	must(display.SegmentTest(true)) // Segment test, (on) → error
	time.Sleep(2 * time.Second)
	must(display.SegmentTest(false))

	status, err := display.ReadStatus() // Read the status byte, () → (uint8, error)
	must(err)
	flag, err := display.PowerResetFlag() // Read the PR flag, () → (bool, error)
	must(err)
	fmt.Printf("status 0x%02X power reset %v\n", status, flag)
	must(display.Clear()) // Blank all digits, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
