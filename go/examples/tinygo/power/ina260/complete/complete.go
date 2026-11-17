//go:build tinygo

// INA260 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the INA260Full API: identity registers,
// configuration, all four primary readings, status flags, alert setup,
// shutdown/wake, and reset.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil) // Create I2C connection, (i2c, addr=0x40) → (*I2CConnection)
	chip, err := power.NewINA260Full(conn)                   // Create INA260 driver, (connection) → (*INA260Full, error)
	if err != nil {
		panic(err)
	}

	mfr, err := chip.ManufacturerID() // Read manufacturer ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("manufacturer_id: 0x%04X\n", mfr) // expect 0x5449

	die, err := chip.DieID() // Read die ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("die_id: 0x%04X\n", die) // (value >> 4) == 0x227

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, ish_ct 0–7, mode 0–7) → error
		panic(err)
	}

	v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("voltage: %.3f V\n", v)

	c, err := chip.Current() // Read load current, () → (float32 A, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("current: %.6f A\n", c)

	p, err := chip.Power() // Read load power, () → (float32 W, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("power: %.6f W\n", p)

	ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("conversion_ready: %v\n", ready)

	ovf, err := chip.Overflow() // Check math overflow, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("overflow: %v\n", ovf)

	if err := chip.SetAlert(power.INA260BOL, 5.0, false, false); err != nil { // Set bus over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetAlert(power.INA260OCL, 5.0, false, false); err != nil { // Set over-current alert, (function, limit A, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.INA260UCL, -5.0, false, false); err != nil { // Set under-current alert, (function, limit A, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.INA260BUL, 1.0, false, false); err != nil { // Set bus under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.INA260POL, 1.0, false, false); err != nil { // Set power over-limit alert, (function, limit W, polarity=false, latch=false) → error
		panic(err)
	}

	if err := chip.ClearAlert(); err != nil { // Disable all alerts, () → error
		panic(err)
	}
	if err := chip.Trigger(power.INA260ModeShuntBusTriggered); err != nil { // One triggered conversion, (mode) → error
		panic(err)
	}

	if err := chip.Shutdown(); err != nil { // Enter power-down mode, () → error
		panic(err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := chip.Wake(); err != nil { // Restore saved mode, () → error
		panic(err)
	}

	if err := chip.Reset(); err != nil { // Reset all registers, () → error
		panic(err)
	}
}
