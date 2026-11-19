//go:build tinygo

// HS30xx hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a HS30xx on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
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
	conn := connection.NewI2CConnection(i2c, 0x44, nil, nil)
	chip := humidity.NewHS30xxFull(conn)

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	t, rh, err := chip.Read()
	check("read", err == nil)
	check("temperature range", t >= -40 && t <= 125)
	check("humidity range", rh >= 0 && rh <= 100)
	t, err = chip.ReadTemperature()
	check("read_temperature range", err == nil && t >= -40 && t <= 125)
	rh, err = chip.ReadHumidity()
	check("read_humidity range", err == nil && rh >= 0 && rh <= 100)
	rhRaw, tRaw, status, err := chip.ReadRaw()
	check("raw ranges", err == nil && rhRaw <= 16383 && tRaw <= 16383)
	check("raw status valid", status == 0)

	check("set wait 60 ms", chip.SetMeasurementWait(60*time.Millisecond) == nil)
	_, err = chip.ReadHumidity()
	check("read with 60 ms wait", err == nil)
	check("set wait 40 ms", chip.SetMeasurementWait(40*time.Millisecond) == nil)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)

	// NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
