//go:build tinygo

// DS3502 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	println(what+":", err)
}

func get(p *adcdac.DS3502Minimal) uint8 {
	v, _ := p.GetWiper()
	return v
}

func volt(p *adcdac.DS3502Minimal) float32 {
	v, _ := p.GetVoltage()
	return v
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, adcdac.DS3502I2CAddress, nil, nil)
	defer conn.Close()

	pot, err := adcdac.NewDS3502Minimal(conn, 15.0, 0.0)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", pot != nil)
	check("wiper_roundtrip", pot.SetWiper(100) == nil && get(pot) == 100)
	pot.SetWiper(300)
	check("wiper_clamps_127", get(pot) == 127)
	check("set_voltage", pot.SetVoltage(6.0) == nil && near(volt(pot), 6.0, 0.1))

	full, err := adcdac.NewDS3502Full(conn, 15.0, 0.0)
	if err != nil {
		fail("init full", err)
		return
	}
	eeprom, _ := full.GetMode()
	check("init_mode_sram", !eeprom)
	full.SetRatio(0.5)
	ratio, _ := full.GetRatio()
	check("ratio_half", get(full.DS3502Minimal) == 64 && near(ratio, 0.5, 0.01))
	full.SetWiper(100)
	rl, _ := full.GetResistance('L')
	rh, _ := full.GetResistance('H')
	check("resistance_nominal", near(rl+rh, 10000.0, 1.0))
	n, _ := full.Increment(5)
	check("increment", n == 105 && get(full.DS3502Minimal) == 105)
	n, _ = full.Increment(-500)
	check("increment_clamps", n == 0)
	full.SetMode(true)
	eeprom, _ = full.GetMode()
	check("set_mode_eeprom", eeprom)
	full.SetMode(false)
	eeprom, _ = full.GetMode()
	check("set_mode_sram", !eeprom)
	check("wait_ready", full.WaitReady(30) == nil)
	// StoreWiper is not exercised: it consumes one of the EEPROM's limited write cycles.

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
