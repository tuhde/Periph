//go:build linux && !tinygo

// INA226 complete example — Linux host.
//
// Exercises every method in the INA226Full API: identity registers,
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

	chip, err := power.NewINA226Full(conn, 0.1, 2.0) // Create INA226 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA226Full, error)
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
	// expect 0x2260

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}
	// sets 16-sample averaging, 1.1 ms bus/shunt conversion, continuous shunt+bus

	v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("voltage: %.3f V\n", v)
	// converts raw bus register to volts (1.25 mV LSB)

	sv, err := chip.ShuntVoltage() // Read shunt voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("shunt_voltage: %.6f V\n", sv)
	// converts raw signed shunt register to volts (2.5 µV LSB)

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

	if err := chip.SetAlert(power.BOL, 5.0, false, false); err != nil { // Set bus over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when bus voltage > 5.0 V; active-low, transparent
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetAlert(power.SOL, 0.1, false, false); err != nil { // Set shunt over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when shunt voltage > 0.1 V
	if err := chip.SetAlert(power.SUL, -0.1, false, false); err != nil { // Set shunt under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when shunt voltage < -0.1 V
	if err := chip.SetAlert(power.BUL, 1.0, false, false); err != nil { // Set bus under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when bus voltage < 1.0 V
	if err := chip.SetAlert(power.POL, 1.0, false, false); err != nil { // Set power over-limit alert, (function, limit W, polarity=false, latch=false) → error
		panic(err)
	}
	// arms ALERT pin when power > 1.0 W

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
