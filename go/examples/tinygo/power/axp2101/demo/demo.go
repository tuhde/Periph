//go:build tinygo

// AXP2101 demo example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = time.Second

func main() {
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x34, nil, nil)
	check(err)
	chip, err := power.NewAXP2101Full(conn) // Create AXP2101 Full driver, (conn) → (*AXP2101Full, error)
	check(err)

	events := []struct {
		mask uint32
		text string
	}{
		{power.AXP2101SourceVbusInsert, "VBUS inserted"},
		{power.AXP2101SourceVbusRemove, "VBUS removed"},
		{power.AXP2101SourceChargeStart, "charging started"},
		{power.AXP2101SourceChargeDone, "charging done"},
		{power.AXP2101SourcePwronShort, "power key short press"},
		{power.AXP2101SourceSocWarnL2, "battery warning level 2"},
	}

	// --- Report why the board started and what every rail is doing ---
	// eFuse defaults differ per product, so the rails are read, never assumed.
	src, err := chip.PowerOnSource() // Read power-on flags, () → (uint8, error)
	check(err)
	fmt.Printf("power-on source: 0x%02X\n", src)
	for _, name := range []string{"dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
		"bldo1", "bldo2", "cpusldo", "dldo1", "dldo2"} {
		enabled, volts, err := chip.GetRail(name) // Read rail state, (name) → (bool, float64 V, error)
		check(err)
		fmt.Printf("%-8s %-3v %.2f V\n", name, enabled, volts)
	}

	// --- Program a conservative 4.2 V / 500 mA charge profile ---
	// 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
	// limit lets a USB charger feed both the system and the charger.
	check(chip.SetChargeVoltage(4.2))      // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → error
	check(chip.SetChargeCurrent(0.5))      // Set charge current, (amps 0–1.5) → error
	check(chip.SetInputCurrentLimit(1.5))  // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → error
	check(chip.SetTermination(0.05, true)) // Set termination current, (amps 0–0.2, enabled) → error

	// --- Arm low-battery warnings and the power events of interest ---
	// The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
	check(chip.ConfigureBatteryWarning(15, 5)) // Set warning levels, (level2 %, level1 %) → error
	for _, e := range events {
		check(chip.EnableInterrupt(e.mask)) // Enable interrupt source, (source) → error
	}
	_, err = chip.PollInterrupt() // Clear stale status, () → (uint32, error)
	check(err)

	// --- Monitor for 120 s ---
	// One status line per second; PollInterrupt returns and clears every pending event.
	for i := 0; i < 120; i++ {
		d, err := chip.ReadAll() // Read all measurements, () → (struct, error)
		check(err)
		st, err := chip.Status() // Read status flags, () → (struct, error)
		check(err)
		fmt.Printf("%.3f V %3d %%  vbus %v %.2f V  sys %.2f V  die %.1f C  state %d  %s\n",
			d.BatteryVoltage, d.BatteryPercent, d.VbusPresent, d.VbusVoltage,
			d.SystemVoltage, d.DieTemperature, d.ChargeState, st.BatteryDirection)
		got, err := chip.PollInterrupt() // Read and clear events, () → (uint32, error)
		check(err)
		for _, e := range events {
			if got&e.mask != 0 {
				fmt.Println("event: " + e.text)
			}
		}
		time.Sleep(time.Second)
	}
}
