//go:build tinygo

// MPL3115A2 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MPL3115A2 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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
	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil)
	chip := pressure.NewMPL3115A2Full(conn)

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

	check("init", chip.Init() == nil)
	id, err := chip.WhoAmI()
	check("who_am_i", err == nil && id == 0xC4)
	p, t, err := chip.Read()
	check("read ok", err == nil)
	check("read_pressure_range", p > 20000 && p < 110000)
	check("read_temperature_range", t > -40 && t < 85)
	ps, err := chip.ReadPressure()
	check("read_pressure_single", err == nil && ps > 20000 && ps < 110000)
	ts, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && ts > -40 && ts < 85)
	for _, ratio := range []uint8{1, 8, 128} {
		check("set_oversample", chip.SetOversample(ratio) == nil)
		check("oversample_roundtrip", chip.GetOversample() == ratio)
		pr, err := chip.ReadPressure()
		check("read_at_osr", err == nil && pr > 20000 && pr < 110000)
	}
	check("set_oversample_16", chip.SetOversample(16) == nil)
	check("set_sea_level_pressure", chip.SetSeaLevelPressure(101325) == nil)
	alt, err := chip.ReadAltitude()
	check("read_altitude_range", err == nil && alt > -500 && alt < 9000)
	check("set_mode_barometer", chip.SetMode(false) == nil)
	check("start_continuous", chip.StartContinuous(0) == nil)
	active, err := chip.IsActive()
	check("is_active", err == nil && active)
	v, _, err := chip.ReadContinuous()
	check("read_continuous", err == nil && v > 20000 && v < 110000)
	check("stop_continuous", chip.StopContinuous() == nil)
	active, err = chip.IsActive()
	check("is_standby", err == nil && !active)
	check("fifo_setup", chip.FIFOSetup(pressure.MPL3115A2FIFOCircular, 4) == nil)
	check("start_continuous_fifo", chip.StartContinuous(0) == nil)
	time.Sleep(5 * time.Second)
	_, _, cnt, err := chip.FIFOStatus()
	check("fifo_collects", err == nil && cnt >= 2)
	samples, err := chip.FIFORead()
	check("fifo_read_count", err == nil && len(samples) >= 2)
	check("stop_continuous_fifo", chip.StopContinuous() == nil)
	check("fifo_off", chip.FIFOSetup(pressure.MPL3115A2FIFOOff, 0) == nil)
	check("enable_interrupt", chip.EnableInterrupt(pressure.MPL3115A2SourceDRDY, 1) == nil)
	check("disable_interrupt", chip.DisableInterrupt(pressure.MPL3115A2SourceDRDY) == nil)
	check("reset", chip.Reset() == nil)
	pr, err := chip.ReadPressure()
	check("read_after_reset", err == nil && pr > 20000 && pr < 110000)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
