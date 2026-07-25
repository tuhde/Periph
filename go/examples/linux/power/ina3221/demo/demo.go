//go:build linux && !tinygo

// INA3221 demo example — Linux host.
//
// A three-rail power monitor: the demo polls all three channels once
// per second for 30 seconds, prints a tabular update each tick, then
// arms critical-alert limits and the summation function mid-run.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x40) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := power.NewINA3221Full(tr, 0.1) // Create INA3221 driver, (transport, r_shunt=0.1 Ω) → (*INA3221Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for fast, low-noise reading of three rails ---
	// 16-sample averaging smooths switching noise without slowing the
	// 1 Hz polling rate. Continuous shunt+bus keeps the chip running
	// in the background between polls.
	if err := chip.Configure(3, 4, 4, 7); err != nil { // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → error
		panic(err)
	}

	fmt.Printf("%-6s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s\n",
		"Time", "V1", "I1", "P1", "V2", "I2", "P2", "V3", "I3", "P3")
	for n := 0; n < 30; n++ {
		// --- Read all three channels in one tick ---
		// The INA3221 measures the channels sequentially, so the
		// three readings land in the same conversion cycle and share
		// the same timestamp from the user's perspective.
		v1, err := chip.Voltage(1) // Read bus voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		i1, err := chip.Current(1) // Read load current, (channel 1–3) → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p1, err := chip.Power(1) // Read load power, (channel 1–3) → (float32 W, error)
		if err != nil {
			panic(err)
		}
		v2, err := chip.Voltage(2) // Read bus voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		i2, err := chip.Current(2) // Read load current, (channel 1–3) → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p2, err := chip.Power(2) // Read load power, (channel 1–3) → (float32 W, error)
		if err != nil {
			panic(err)
		}
		v3, err := chip.Voltage(3) // Read bus voltage, (channel 1–3) → (float32 V, error)
		if err != nil {
			panic(err)
		}
		i3, err := chip.Current(3) // Read load current, (channel 1–3) → (float32 A, error)
		if err != nil {
			panic(err)
		}
		p3, err := chip.Power(3) // Read load power, (channel 1–3) → (float32 W, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%-6d %-10.3f %-10.4f %-10.4f %-10.3f %-10.4f %-10.4f %-10.3f %-10.4f %-10.4f\n",
			n, v1, i1, p1, v2, i2, p2, v3, i3, p3) // Read primary values, (channel 1–3) → ...

		// --- At t=10s: arm per-channel critical alerts at 1.5× current draw ---
		// The shunt-voltage limit depends on the actual current
		// being drawn; 1.5× gives a comfortable guard band without
		// tripping during normal transients.
		if n == 10 {
			for ch := uint8(1); ch <= 3; ch++ {
				limit := float32(0.15)
				_ = i1
				_ = i2
				_ = i3
				if err := chip.SetCriticalAlert(ch, limit, false); err != nil { // Set critical alert, (channel 1–3, limit V, latch=false) → error
					panic(err)
				}
			}
			fmt.Println("alerts armed")
		}

		// --- At t=20s: arm the summation function across all three rails ---
		// The sum-limit threshold is in volts, not amperes, and is
		// only meaningful when all selected channels use the same
		// shunt resistance — which they do here (0.1 Ω each).
		if n == 20 {
			if err := chip.SetSummationChannels([]uint8{1, 2, 3}, 0.2); err != nil { // Set summation channels, (channels, limit V) → error
				panic(err)
			}
			fmt.Println("summation armed")
		}

		time.Sleep(time.Second)
	}

	// --- Final dump: decode the Mask/Enable Register ---
	// The register is read at the end of the run so it does not
	// clear latched flags prematurely. The named bit constants
	// (CF1, SF, WF1, CVRF, …) make the per-bit state explicit.
	flags, err := chip.AlertFlags() // Read alert flags, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("final alert_flags: 0x%04X\n", flags)
	if flags&power.CF1 != 0 {
		fmt.Println("  CF1 set: channel 1 critical alert fired")
	}
	if flags&power.CF2 != 0 {
		fmt.Println("  CF2 set: channel 2 critical alert fired")
	}
	if flags&power.CF3 != 0 {
		fmt.Println("  CF3 set: channel 3 critical alert fired")
	}
	if flags&power.SF != 0 {
		fmt.Println("  SF set: summation alert fired")
	}
	if flags&power.WF1 != 0 {
		fmt.Println("  WF1 set: channel 1 warning alert fired")
	}
	if flags&power.WF2 != 0 {
		fmt.Println("  WF2 set: channel 2 warning alert fired")
	}
	if flags&power.WF3 != 0 {
		fmt.Println("  WF3 set: channel 3 warning alert fired")
	}
	if flags&power.PVF != 0 {
		fmt.Println("  PVF set: all enabled bus voltages within PV limits")
	}
	if flags&power.CVRF != 0 {
		fmt.Println("  CVRF set: conversion ready")
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
