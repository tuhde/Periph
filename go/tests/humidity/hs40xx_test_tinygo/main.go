//go:build tinygo

// HS40xx hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a HS40xx on I2C1 (GP4 = SDA,
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
	conn := connection.NewI2CConnection(i2c, 0x54, nil, nil)
	chip := humidity.NewHS40xxFull(conn)

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
	rhRaw, tRaw, err := chip.ReadRaw()
	check("raw ranges", err == nil && rhRaw <= 16383 && tRaw <= 16383)
	id, err := chip.ReadID()
	check("id nonzero", err == nil && id != 0)

	check("set resolution 12/10", chip.SetResolution(12, 10) == nil)
	hb, tb, err := chip.GetResolution()
	check("resolution readback", err == nil && hb == 12 && tb == 10)
	check("restore resolution", chip.SetResolution(14, 14) == nil)

	check("start periodic", chip.StartPeriodic(0.5) == nil)
	time.Sleep(time.Second)
	p, err := chip.ReadPeriodic()
	check("periodic humidity", err == nil && p.HumidityPct >= 0 && p.HumidityPct <= 100)
	check("stop periodic", chip.StopPeriodic() == nil)
	check("soft reset", chip.SoftReset() == nil)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
