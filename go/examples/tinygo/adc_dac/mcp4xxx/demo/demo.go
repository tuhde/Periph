//go:build tinygo

// MCP4XXX demo — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func main() {
	const part = "MCP4532"
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}
	defAddr, err := adcdac.MCP4XXXDefaultAddress(part)
	if err != nil {
		panic(err)
	}
	conn := connection.NewI2CConnection(i2c, defAddr, nil, nil) // Create I2C connection, (i2c, addr) → (*I2CConnection)

	// --- Identify the family member ---
	// One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
	pot, err := adcdac.NewMCP4XXXFull(conn, part, 10000, 75, nil, 1) // Create MCP4XXX driver, (connection, part, rAB=10000 Ω, rWiper=75 Ω, hvc=nil, hvcSettleMs=1) → (*MCP4XXXFull, error)
	if err != nil {
		panic(err)
	}
	must(0, pot.Init()) // Bus check, () → error
	kind, mem := "rheostat", "RAM"
	if pot.IsPotentiometer() { // Terminal A on a pin, () → bool
		kind = "pot"
	}
	if pot.IsNonvolatile() { // Has EEPROM, () → bool
		mem = "EEPROM"
	}
	fmt.Println(part, pot.Channels(), "network(s)", pot.Steps(), "steps", kind, mem)        // Part properties, () → int
	fmt.Println("power-up wiper", must(pot.GetWiper(0)), must(pot.GetResistance(0)), "ohm") // Read wiper, (channel=0) → (int, error)

	// --- Set 2.2 kΩ and report the quantisation error ---
	// The wiper snaps to the nearest step of RAB / max code.
	must(0, pot.SetResistance(2200, 0))  // Set W-B resistance, (ohms, channel=0) → error
	actual := must(pot.GetResistance(0)) // Read W-B resistance, (channel=0) → (float64 Ω, error)
	fmt.Printf("target=2200 ohm  code=%d  actual=%.1f ohm  error=%.1f ohm\n", must(pot.GetWiper(0)), actual, actual-2200)

	// --- Step the wiper up and down ---
	// Increment / Decrement send one command byte per step in a single transaction and
	// saturate at full scale and zero scale.
	for _, s := range []struct {
		steps int
		up    bool
	}{{8, true}, {8, true}, {4, false}, {10, true}} {
		var code int
		if s.up {
			code = must(pot.Increment(s.steps, 0)) // Step wiper, (steps, channel=0) → (int, error)
		} else {
			code = must(pot.Decrement(s.steps, 0)) // Step wiper, (steps, channel=0) → (int, error)
		}
		fmt.Printf("up=%v steps=%d -> code=%d  R_WB=%.1f ohm\n", s.up, s.steps, code, must(pot.GetResistance(0)))
	}

	// --- Ratios ---
	// The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
	for _, ratio := range []float64{0.0, 0.25, 0.5, 0.75, 1.0} {
		must(0, pot.SetRatio(ratio, 0)) // Set wiper ratio, (ratio 0.0-1.0, channel=0) → error
		fmt.Printf("ratio=%.2f  code=%d  R_WB=%.1f", ratio, must(pot.GetWiper(0)), must(pot.GetResistance(0)))
		if pot.IsPotentiometer() {
			fmt.Printf("  R_WA=%.1f", must(pot.GetResistanceWA(0))) // Read W-A resistance, (channel=0) → (float64 Ω, error)
		}
		fmt.Println()
	}

	// --- Dual parts: drive both networks ---
	if pot.Channels() == 2 {
		must(0, pot.SetBoth(32, 96)) // Set both wipers, (code0, code1) → error
		fmt.Println("both:", must(pot.GetWiper(0)), must(pot.GetWiper(1)), "->", must(pot.Increment(5, 1)), must(pot.Decrement(2, 1)))
	}

	// --- Shutdown and terminal disconnect ---
	// Shutdown ties W to B and opens A without losing the wiper; SetTerminals(w = false) floats W.
	must(0, pot.Shutdown(0))                                                        // Shut network down, (channel=0) → error
	fmt.Println("shutdown", must(pot.IsShutdown(0)), "code", must(pot.GetWiper(0))) // Read state, (channel=0) → (bool, error)
	time.Sleep(time.Second)
	must(0, pot.Resume(0))                          // Leave shutdown, (channel=0) → error
	must(0, pot.SetTerminals(true, false, true, 0)) // Disconnect the wiper, (a, w, b, channel=0) → error
	a, w, b, _ := pot.GetTerminals(0)               // Read terminals, (channel=0) → (a, w, b bool, error)
	fmt.Println("terminals", a, w, b)
	must(0, pot.SetTerminals(true, true, true, 0)) // Reconnect all terminals, (a, w, b, channel=0) → error

	// --- Non-volatile parts: EEPROM wiper and data ---
	// StoreWiper makes the setting survive power cycles; every EEPROM write uses one of the
	// 1 000 000 write cycles, so do not do this in a loop.
	if pot.IsNonvolatile() {
		fmt.Printf("%+v\n", must(pot.ReadStatus()))                                           // Read STATUS, () → (MCP4XXXStatus, error)
		fmt.Println("stored", must(pot.StoreWiper(0)))                                        // Store the wiper in EEPROM, (channel=0) → (int, error)
		must(0, pot.WriteData(0, 0x155))                                                      // Write calibration marker, (index 0-9, value 0-0x1FF) → error
		fmt.Printf("data0 %#x nv wiper %d\n", must(pot.ReadData(0)), must(pot.GetNVWiper(0))) // Read back, (index) → (int, error)
	}

	// --- Back to the power-up state ---
	must(0, pot.Reset()) // Emulate power-on reset, () → error
	fmt.Println("after reset:", must(pot.GetWiper(0)))
}
