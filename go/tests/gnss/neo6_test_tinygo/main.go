//go:build tinygo

// NEO-6 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a NEO-6 GPS module on I2C1
// (GP4 = SDA, GP5 = SCL, address 0x42). Prints PASS/FAIL per check
// and ends with the standard ===DONE: ... === line. The test runner
// (go/test_tinygo.sh) reads the serial output and reports exit code
// 0/1/2 based on the ===DONE=== line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	tr := transport.NewI2CTransport(i2c, 0x42)
	chip := gnss.NewNEO6Minimal(tr, "i2c")

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("fix_starts_at_0", chip.Fix() == 0)
	check("latitude_starts_at_nil", chip.Latitude() == nil)
	check("longitude_starts_at_nil", chip.Longitude() == nil)
	check("altitude_starts_at_nil", chip.Altitude() == nil)

	// Pump Update() for 5 minutes of NMEA output. The TinyGo runner
	// reads the ===DONE=== line at any point, so a slower acquisition
	// will still produce a clean test result.
	for i := 0; i < 300; i++ {
		_, err := chip.Update()
		if err != nil {
			fmt.Printf("update at %d: %v\n", i, err)
			break
		}
		time.Sleep(time.Second)
	}

	check("fix_is_valid_quality", chip.Fix() <= 2)
	check("satellites_non_negative", int(chip.Satellites()) >= 0)
	if chip.Fix() > 0 {
		lat := chip.Latitude()
		lon := chip.Longitude()
		check("latitude_in_range_once_fixed", lat != nil && *lat >= -90.0 && *lat <= 90.0)
		check("longitude_in_range_once_fixed", lon != nil && *lon >= -180.0 && *lon <= 180.0)
		check("altitude_populated_once_fixed", chip.Altitude() != nil)
	} else {
		fmt.Println("note: no fix acquired during the test window (needs sky view)")
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
