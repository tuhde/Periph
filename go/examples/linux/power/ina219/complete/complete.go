//go:build linux && !tinygo

// INA219 complete example — Linux host.
//
// Exercises every method in the INA219Full API: configuration of bus
// range, PGA, ADC resolution/averaging, and mode; all four primary
// readings; conversion-ready and overflow flags; shutdown/wake; reset;
// and single-shot trigger.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := power.NewINA219Full(conn, 0.1, 2.0) // Create INA219 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA219Full, error)
	if err != nil {
		panic(err)
	}

	// 32 V bus range, PGA ÷8, 12-bit ADC, continuous shunt+bus
	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}
	// writes Configuration Register then re-writes Calibration Register

	v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("voltage: %.3f V\n", v)
	// (raw >> 3) × 4 mV LSB

	sv, err := chip.ShuntVoltage() // Read shunt voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("shunt_voltage: %.6f V\n", sv)
	// raw signed × 10 µV LSB

	c, err := chip.Current() // Read load current, () → (float32 A, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("current: %.6f A\n", c)
	// raw signed × current_lsb

	p, err := chip.Power() // Read load power, () → (float32 W, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("power: %.6f W\n", p)
	// raw × 20 × current_lsb

	ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("conversion_ready: %v\n", ready)
	// reads CNVR bit (bit 1) from Bus Voltage register

	ovf, err := chip.Overflow() // Check math overflow, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("overflow: %v\n", ovf)
	// reads OVF bit (bit 0) from Bus Voltage register

	// Triggered mode: writing MODE = 1 starts a single-shot shunt conversion
	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_TRIG); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}
	// single-shot triggered mode
	if err := chip.Trigger(); err != nil { // Re-trigger conversion, () → error
		panic(err)
	}
	// re-writes Configuration Register, which restarts a triggered conversion

	// Restore continuous mode for shutdown/wake/reset demo
	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}

	if err := chip.Shutdown(); err != nil { // Enter power-down mode, () → error
		panic(err)
	}
	// sets MODE = 000, saves prior mode for wake
	if err := chip.Wake(); err != nil { // Restore saved mode, () → error
		panic(err)
	}
	// re-writes Configuration Register with saved MODE bits

	if err := chip.Reset(); err != nil { // Reset all registers, () → error
		panic(err)
	}
	// sets RST bit, then re-writes Calibration Register
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
