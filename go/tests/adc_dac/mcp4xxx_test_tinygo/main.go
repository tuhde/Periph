//go:build tinygo

// MCP4XXX hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MCP4XXX on I2C1 (GP4 = SDA, GP5 = SCL); the part under
// test is the testPart constant. Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

// testPart is the part under test; its default address is used.
const testPart = "MCP4532"

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
	defAddr, err := adcdac.MCP4XXXDefaultAddress(testPart)
	if err != nil {
		fmt.Printf("FAIL part: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, defAddr, nil, nil)
	chip, err := adcdac.NewMCP4XXXFull(conn, testPart, 10000, 75, nil, 1)
	if err != nil {
		fmt.Printf("FAIL driver: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	if err := chip.Init(); err != nil {
		fmt.Println("FAIL init:", err)
	}
	max := chip.Steps() - 1

	// --- Wiper write / readback ---
	chip.SetWiper(100%(max+1), 0)
	v, err := chip.GetWiper(0)
	check("set/get wiper", err == nil && v == 100%(max+1))
	chip.SetWiper(9999, 0)
	v, _ = chip.GetWiper(0)
	check("clamps high", v == max)
	chip.SetWiper(-5, 0)
	v, _ = chip.GetWiper(0)
	check("clamps low", v == 0)
	if chip.Channels() == 2 {
		chip.SetBoth(10, 20)
		v, _ = chip.GetWiper(0)
		check("set_both ch0", v == 10)
		v, _ = chip.GetWiper(1)
		check("set_both ch1", v == 20)
	}

	// --- Resistance and ratio ---
	chip.SetResistance(5075, 0)
	v, _ = chip.GetWiper(0)
	check("set_resistance ~mid", v >= max/2-1 && v <= max/2+1)
	r, _ := chip.GetResistance(0)
	check("get_resistance ~5075", r > 4975 && r < 5175)
	chip.SetRatio(0.5, 0)
	ratio, _ := chip.GetRatio(0)
	check("get_ratio ~0.5", ratio > 0.49 && ratio < 0.51)
	if chip.IsPotentiometer() {
		chip.SetWiper(0, 0)
		rwa, _ := chip.GetResistanceWA(0)
		check("R_WA at zero scale ~RAB", rwa > 9975 && rwa < 10175)
	}

	// --- Increment / decrement (continuous) ---
	chip.SetWiper(max-3, 0)
	v, _ = chip.Increment(10, 0)
	check("increment saturates", v == max)
	chip.SetWiper(3, 0)
	v, _ = chip.Decrement(10, 0)
	check("decrement saturates", v == 0)
	v, _ = chip.Increment(1, 0)
	check("increment 1", v == 1)

	// --- Shutdown / terminals ---
	chip.SetWiper(max/4, 0)
	chip.Shutdown(0)
	down, _ := chip.IsShutdown(0)
	check("is_shutdown", down)
	chip.Resume(0)
	down, _ = chip.IsShutdown(0)
	check("not shutdown", !down)
	v, _ = chip.GetWiper(0)
	check("wiper kept across shutdown", v == max/4)
	chip.SetTerminals(true, false, true, 0)
	a, w, b, _ := chip.GetTerminals(0)
	check("terminals", a && !w && b)
	chip.SetTerminals(true, true, true, 0)
	tcon, _ := chip.GetTCON()
	check("tcon restored", tcon&0xFF == 0xFF)

	// --- Non-volatile parts ---
	if chip.IsNonvolatile() {
		st, err := chip.ReadStatus()
		check("status no busy", err == nil && !st.EEPROMBusy)
		chip.SetWiper(max/2, 0)
		v, err = chip.StoreWiper(0)
		check("store_wiper", err == nil && v == max/2)
		v, _ = chip.GetNVWiper(0)
		check("get_nv_wiper", v == max/2)
		chip.WriteData(0, 0x155)
		v, _ = chip.ReadData(0)
		check("data roundtrip", v == 0x155)
	}

	// --- Reset ---
	chip.SetWiper(1, 0)
	chip.Reset()
	v, _ = chip.GetWiper(0)
	check("reset wiper", v == max/2)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
