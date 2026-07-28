//go:build tinygo

// INA219 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the INA219Full API: configuration of bus
// range, PGA, ADC resolution/averaging, and mode; all four primary
// readings; conversion-ready and overflow flags; trigger; shutdown/wake;
// and reset.
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

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil)         // Create I2C connection, (i2c, addr=0x40) → (*I2CConnection)
	chip, err := power.NewINA219Full(conn, 0.1, 2.0)      // Create INA219 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A) → (*INA219Full, error)
	if err != nil {
		panic(err)
	}

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
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

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_TRIG); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}
	if err := chip.Trigger(); err != nil { // Re-trigger conversion, () → error
		panic(err)
	}

	if err := chip.Configure(power.BRNG_32V, power.PGA_8, power.ADC_12BIT, power.ADC_12BIT, power.MODE_SHUNT_BUS_CONT); err != nil { // Configure chip, (brng, pga, badc, sadc, mode) → error
		panic(err)
	}

	if err := chip.Shutdown(); err != nil { // Enter power-down mode, () → error
		panic(err)
	}
	time.Sleep(50 * time.Millisecond)
	if err := chip.Wake(); err != nil { // Restore saved mode, () → error
		panic(err)
	}

	if err := chip.Reset(); err != nil { // Reset all registers, () → error
		panic(err)
	}
}
