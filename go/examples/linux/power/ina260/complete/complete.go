//go:build linux && !tinygo

// INA260 complete example — Linux host.
//
// Exercises every method in the INA260Full API: identity registers,
// configuration, all four primary readings, status flags, every alert
// function, shutdown/wake, and reset.
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

	chip, err := power.NewINA260Full(conn) // Create INA260 driver, (connection) → (*INA260Full, error)
	if err != nil {
		panic(err)
	}

	mfr, err := chip.ManufacturerID() // Read manufacturer ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("manufacturer_id: 0x%04X\n", mfr)
	// expect 0x5449 ("TI" in ASCII)

	die, err := chip.DieID() // Read die ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("die_id: 0x%04X\n", die)
	// (value >> 4) == 0x227

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, ish_ct 0–7, mode 0–7) → error
		panic(err)
	}
	// sets 64-sample averaging, 1.1 ms bus/shunt conversion, continuous shunt+bus

	v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("voltage: %.3f V\n", v)
	// converts raw bus register to volts (1.25 mV LSB)

	c, err := chip.Current() // Read load current, () → (float32 A, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("current: %.6f A\n", c)
	// converts raw signed current register to amps using current_lsb

	p, err := chip.Power() // Read load power, () → (float32 W, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("power: %.6f W\n", p)
	// raw × 25 × current_lsb

	ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("conversion_ready: %v\n", ready)
	// reads CVRF bit from Mask/Enable register

	ovf, err := chip.Overflow() // Check math overflow, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("overflow: %v\n", ovf)
	// reads OVF bit from Mask/Enable register

	if err := chip.SetAlert(power.INA260BOL, 5.0, false, false); err != nil { // Set bus over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when bus voltage > 5.0 V; active-low, transparent
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetAlert(power.INA260OCL, 5.0, false, false); err != nil { // Set over-current alert, (function, limit A, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when current > 5 A
	if err := chip.SetAlert(power.INA260UCL, -5.0, false, false); err != nil { // Set under-current alert, (function, limit A, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when current < -5 A
	if err := chip.SetAlert(power.INA260BUL, 1.0, false, false); err != nil { // Set bus under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when bus voltage < 1.0 V
	if err := chip.SetAlert(power.INA260POL, 1.0, false, false); err != nil { // Set power over-limit alert, (function, limit W, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when power > 1.0 W

	if err := chip.ClearAlert(); err != nil { // Disable all alerts, () → error
		panic(err)
	}
	if err := chip.Trigger(power.INA260ModeShuntBusTriggered); err != nil { // One triggered conversion, (mode) → error
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
	// sets RST bit; chip returns to power-on defaults (0x6127)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
