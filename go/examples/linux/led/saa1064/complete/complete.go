//go:build linux && !tinygo

// SAA1064 complete example — Linux host.
//
// Calls every SAA1064Full method once.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	display, err := led.NewSAA1064Full(conn) // Create SAA1064 driver, (connection) → (*SAA1064Full, error)
	must(err)

	must(display.ShowDigits([4]int{1, 2, 3, 4})) // Show four digits, (digits [4]int, 0–9 or SAA1064Blank) → error
	// default mapping a=bit0 … g=bit6, dp=bit7
	time.Sleep(time.Second)
	must(display.ShowNumber(42)) // Show a number, (value 0–9999) → error
	// leading zeros are blanked
	must(display.WriteRaw([]byte{0x7F, 0x7F}, 3)) // Write raw segment bytes, (patterns 1–4, firstDigit 1–4) → error
	// digits 3 and 4 show 8
	time.Sleep(time.Second)
	must(display.ShowDigit(2, 7, true)) // Show one digit, (position 1–4, value 0–9 or SAA1064Blank, dp) → error
	// digit 2 = 7 with decimal point
	must(display.SetDecimalPoint(2, false)) // Switch a decimal point, (position 1–4, on) → error
	// keeps the cached segments
	must(display.SetSegmentMap([8]uint8{0, 1, 2, 3, 4, 5, 6, 7})) // Remap segment bits, (map a–g, dp → bit 0–7) → error
	must(display.SetCurrent(21))                                  // Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → error
	// C4–C6
	must(display.SetMode(false)) // Select static/dynamic mode, (dynamic) → error
	// static shows only digits 1 and 2
	time.Sleep(time.Second)
	must(display.SetMode(true))
	must(display.Blank(true, false)) // Blank digit pairs, (digits13, digits24) → error
	// digits 1+3 off
	time.Sleep(time.Second)
	must(display.Blank(false, false))
	must(display.SegmentTest(true)) // Segment test, (on) → error
	// lights every segment
	time.Sleep(time.Second)
	must(display.SegmentTest(false))
	status, err := display.ReadStatus() // Read the status byte, () → (uint8, error)
	must(err)
	flag, err := display.PowerResetFlag() // Read the PR flag, () → (bool, error)
	// set after power-up, clears when read
	must(err)
	fmt.Printf("status 0x%02X power reset %v\n", status, flag)
	must(display.WriteControl(0x47)) // Write the raw control byte, (value 0–127) → error
	// dynamic mode, unblanked, 12 mA
	must(display.Clear()) // Blank all digits, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
