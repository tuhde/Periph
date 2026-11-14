//go:build tinygo

// SHT31 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a SHT31 on I2C1 (GP4 = SDA,
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
	chip, err := humidity.NewSHT31Full(conn)
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

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= 0 && rh <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	rawT, rawRH, err := chip.ReadRaw()
	check("read_raw ok", err == nil && rawT <= 65535 && rawRH <= 65535)

	for _, level := range []string{humidity.SHT31RepeatabilityLow, humidity.SHT31RepeatabilityMedium, humidity.SHT31RepeatabilityHigh} {
		check("set_repeatability "+level, chip.SetRepeatability(level) == nil)
		t2, rh2, err := chip.Read()
		check("repeatability read "+level, err == nil && t2 > -40 && t2 < 125 && rh2 >= 0 && rh2 <= 100)
	}

	_, err = chip.ReadStatus()
	check("status readable", err == nil)
	check("clear_status", chip.ClearStatus() == nil)
	status, err := chip.ReadStatus()
	check("status bit 4 cleared", err == nil && status&0x0010 == 0)

	check("start_periodic", chip.StartPeriodic(2, humidity.SHT31RepeatabilityHigh, false) == nil)
	time.Sleep(1200 * time.Millisecond)
	_, _, ok, err := chip.Fetch()
	check("periodic fetch", err == nil && ok)
	check("stop_periodic", chip.StopPeriodic() == nil)

	check("heater on", chip.SetHeater(true) == nil)
	status, err = chip.ReadStatus()
	check("heater bit set", err == nil && status&0x2000 != 0)
	check("heater off", chip.SetHeater(false) == nil)
	status, err = chip.ReadStatus()
	check("heater bit cleared", err == nil && status&0x2000 == 0)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("soft_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after soft reset", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
