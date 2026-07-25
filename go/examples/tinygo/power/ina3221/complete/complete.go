//go:build tinygo

// INA3221 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the INA3221Full API: identity registers,
// configuration of all three channels, per-channel primary readings,
// channel enable/disable, critical and warning alert limits, summation,
// power-valid thresholds, status flags, shutdown/wake, and reset.
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
	chip, err := power.NewINA3221Full(tr, 0.1)          // Create INA3221 driver, (transport, r_shunt=0.1 Ω) → (*INA3221Full, error)
	if err != nil {
		panic(err)
	}

	mfr, err := chip.ManufacturerID() // Read manufacturer ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("manufacturer_id: 0x%04X\n", mfr)

	die, err := chip.DieID() // Read die ID, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("die_id: 0x%04X\n", die)

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}

	for ch := uint8(1); ch <= 3; ch++ {
		v, err := chip.Voltage(ch) // Read bus voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("CH%d: V=%.3f V\n", ch, v)
	}

	ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("conversion_ready: %v\n", ready)

	for ch := uint8(1); ch <= 3; ch++ {
		if err := chip.SetCriticalAlert(ch, 0.1, false); err != nil { // Set critical alert, (channel 1–3, limit V, latch=false) → error
			panic(err)
		}
		if err := chip.SetWarningAlert(ch, 0.05, false); err != nil { // Set warning alert, (channel 1–3, limit V, latch=false) → error
			panic(err)
		}
	}
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetSummationChannels([]uint8{1, 2, 3}, 0.2); err != nil { // Set summation channels, (channels, limit V) → error
		panic(err)
	}
	sum, err := chip.SummationValue() // Read sum, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("summation: %.6f V\n", sum)

	if err := chip.SetPowerValidLimits(5.5, 4.5); err != nil { // Set power-valid limits, (upper V, lower V) → error
		panic(err)
	}
	pv, err := chip.PowerValid() // Check power-valid flag, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("power_valid: %v\n", pv)

	if err := chip.EnableChannel(1, false); err != nil { // Disable channel, (channel 1–3, enabled=false) → error
		panic(err)
	}
	enabled, err := chip.ChannelEnabled(1) // Check channel enabled, (channel 1–3) → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("CH1 after disable: %v\n", enabled)
	if err := chip.EnableChannel(1, true); err != nil { // Re-enable channel, (channel 1–3, enabled=true) → error
		panic(err)
	}
	enabled, err = chip.ChannelEnabled(1) // Check channel enabled, (channel 1–3) → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("CH1 after re-enable: %v\n", enabled)

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
