//go:build tinygo

// RDA5807M hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an RDA5807M on I2C1 (GP4 = SDA, GP5 = SCL).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	tr := transport.NewI2CTransport(i2c, 0x10)
	fm, err := comms.NewRda5807mFull(tr, 100.0, 8)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

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

	time.Sleep(50 * time.Millisecond)
	ready, err := fm.IsReady()
	check("is_ready", err == nil && ready)

	f, err := fm.Frequency()
	check("frequency_near_100mhz", err == nil && f > 99.8 && f < 100.2)

	if err := fm.SetVolume(8); err != nil {
		fmt.Printf("FAIL set_volume: %v\n", err)
	}
	check("set_volume_ok", err == nil)

	rssi, err := fm.SignalStrength()
	check("signal_strength_in_range", err == nil && rssi <= 127)

	if err := fm.EnableRds(true); err != nil {
		fmt.Printf("FAIL rds: %v\n", err)
	}
	_, err = fm.RdsReady()
	check("rds_ready_ok", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
