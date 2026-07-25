//go:build tinygo

// INA226 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the INA226Full API: identity registers,
// configuration, all four primary readings, status flags, alert setup,
// shutdown/wake, and reset.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x40)         // Create I2C transport, (i2c, addr=0x40) → (*I2CTransport)
	chip, err := power.NewINA226Full(tr, 0.1, 2.0)      // Create INA226 driver, (transport, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA226Full, error)
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
	fmt.Printf("die_id: 0x%04X\n", die) // expect 0x2260

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}

	v, err := chip.Voltage() // Read bus voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("voltage: %.3f V\n", v)

	sv, err := chip.ShuntVoltage() // Read shunt voltage, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("shunt_voltage: %.6f V\n", sv)

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

	if err := chip.SetAlert(power.BOL, 5.0, false, false); err != nil { // Set bus over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetAlert(power.SOL, 0.1, false, false); err != nil { // Set shunt over-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.SUL, -0.1, false, false); err != nil { // Set shunt under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.BUL, 1.0, false, false); err != nil { // Set bus under-limit alert, (function, limit V, polarity=false, latch=false) → error
		panic(err)
	}
	if err := chip.SetAlert(power.POL, 1.0, false, false); err != nil { // Set power over-limit alert, (function, limit W, polarity=false, latch=false) → error
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
