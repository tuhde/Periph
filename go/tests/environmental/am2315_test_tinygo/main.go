//go:build tinygo

// AM2315 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a AM2315 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
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
	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)
	chip := environmental.NewAM2315Full(conn)

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

	check("device info", func() bool { m, _, _, err := chip.ReadDeviceInfo(); return err == nil && m != 0xFFFF }())

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t >= -40 && t <= 125)
	check("humidity range", rh >= 0 && rh <= 100)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc >= -40 && tc <= 125)
	rawRH, rawT, err := chip.ReadRaw()
	check("raw ranges", err == nil && rawRH <= 1000 && rawT&0x7FFF <= 1250)
	ft, fh, err := chip.ReadFresh()
	check("read_fresh range", err == nil && ft >= -40 && ft <= 125 && fh >= 0 && fh <= 100)
	regs, err := chip.ReadRegisters(0x00, 4)
	check("read_registers length", err == nil && len(regs) == 4)

	check("write_user_registers", chip.WriteUserRegisters(0x1234, 0xABCD) == nil)
	u1, u2, err := chip.ReadUserRegisters()
	check("user registers round trip", err == nil && u1 == 0x1234 && u2 == 0xABCD)
	check("clear user registers", chip.WriteUserRegisters(0, 0) == nil)
	chip.Wake()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
