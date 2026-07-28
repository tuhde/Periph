//go:build linux && !tinygo

// INA3221 complete example — Linux host.
//
// Exercises every method in the INA3221Full API: identity registers,
// configuration of all three channels, per-channel primary readings,
// channel enable/disable, critical and warning alert limits, summation,
// power-valid thresholds, status flags, shutdown/wake, and reset.
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

	chip, err := power.NewINA3221Full(conn, 0.1) // Create INA3221 driver, (connection, r_shunt=0.1 Ω) → (*INA3221Full, error)
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
	fmt.Printf("die_id: 0x%04X\n", die) // expect 0x3220

	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}
	// sets 16-sample averaging, 1.1 ms bus/shunt conversion, continuous shunt+bus

	for _, ch := range []uint8{1, 2, 3} {
		v, err := chip.Voltage(ch) // Read bus voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		sv, err := chip.ShuntVoltage(ch) // Read shunt voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		c, err := chip.Current(ch) // Read load current, (channel 1–3) → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p, err := chip.Power(ch) // Read load power, (channel 1–3) → (float32 W, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("CH%d: V=%.3f V  SV=%.6f V  I=%.4f A  P=%.4f W\n", ch, v, sv, c, p)
	}

	for ch := uint8(1); ch <= 3; ch++ {
		enabled, err := chip.ChannelEnabled(ch) // Check channel enabled, (channel 1–3) → (bool, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("CH%d enabled: %v\n", ch, enabled)
	}

	ready, err := chip.ConversionReady() // Check conversion done, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("conversion_ready: %v\n", ready)
	// reads CVRF bit from Mask/Enable register

	for ch := uint8(1); ch <= 3; ch++ {
		if err := chip.SetCriticalAlert(ch, 0.1, false); err != nil { // Set critical alert, (channel 1–3, limit V, latch=false) → error
			panic(err)
		}
		// arms Critical alert when |shunt| > 0.1 V
		if err := chip.SetWarningAlert(ch, 0.05, false); err != nil { // Set warning alert, (channel 1–3, limit V, latch=false) → error
			panic(err)
		}
		// arms Warning alert when |shunt| > 0.05 V
	}
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("alert_flags: 0x%04X\n", flags)

	if err := chip.SetSummationChannels([]uint8{1, 2, 3}, 0.2); err != nil { // Set summation channels, (channels, limit V) → error
		panic(err)
	}
	// sums shunt voltages of channels 1, 2, 3; arms SF flag at 0.2 V
	sum, err := chip.SummationValue() // Read sum, () → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("summation: %.6f V\n", sum)

	if err := chip.SetPowerValidLimits(5.5, 4.5); err != nil { // Set power-valid limits, (upper V, lower V) → error
		panic(err)
	}
	// arms PVF when all enabled bus voltages are within 4.5–5.5 V
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
	// sets MODE = 000, saves prior mode for wake
	if err := chip.Wake(); err != nil { // Restore saved mode, () → error
		panic(err)
	}
	// re-writes Configuration Register with saved MODE bits

	if err := chip.Reset(); err != nil { // Reset all registers, () → error
		panic(err)
	}
	// sets RST bit — alert limits and Mask/Enable reset to defaults
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
