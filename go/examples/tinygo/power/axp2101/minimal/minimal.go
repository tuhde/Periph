//go:build tinygo

// AXP2101 minimal example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = time.Second

func main() {
	i2c := machine.I2C1
	check(i2c.Configure(machine.I2CConfig{SDA: machine.GP4, SCL: machine.GP5, Frequency: 400_000}))
	conn := connection.NewI2CConnection(i2c, 0x34, nil, nil)
	chip, err := power.NewAXP2101Minimal(conn) // Create AXP2101 driver, (conn) → (*AXP2101Minimal, error)
	check(err)
	for i := 0; i < 10; i++ {
		v, err := chip.BatteryVoltage() // Read battery voltage, () → (float64 V, error)
		check(err)
		pct, err := chip.BatteryPercent() // Read state of charge, () → (int %, error)
		check(err)
		state, err := chip.ChargeState() // Read charge state, () → (int, error)
		check(err)
		vbus, err := chip.VbusPresent() // Check input source, () → (bool, error)
		check(err)
		fmt.Printf("%.3f V  %d %%  state=%d  vbus=%v\n", v, pct, state, vbus)
		time.Sleep(time.Second)
	}
}
