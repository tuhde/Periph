//go:build linux && !tinygo

// DRV2605 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	m, err := motor.NewDRV2605Minimal(conn, false) // Create DRV2605 driver, (conn, lra=false) → (*DRV2605Minimal, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", m != nil)
	check("effect_no_error", m.PlayEffect(motor.DRV2605EffectStrongClick) == nil) // Play a ROM effect, (effectID 1–123) → error
	time.Sleep(500 * time.Millisecond)
	playing, err := m.IsPlaying() // Check playback, () → (bool, error)
	check("effect_finishes", err == nil && !playing)

	full, err := motor.NewDRV2605Full(conn, false) // Create DRV2605 Full driver, (conn, lra=false) → (*DRV2605Full, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	id, err := full.ReadDeviceID() // Read DEVICE_ID, () → (uint8, error)
	check("device_id", err == nil && id == 3)
	check("sequence_no_error", full.PlaySequence([]motor.DRV2605SequenceStep{motor.DRV2605Effect(1), motor.DRV2605Wait(100), motor.DRV2605Effect(1)}) == nil) // Play a sequence, (items ≤ 8) → error
	done, err := full.WaitDone(3000)                                                                                                                          // Wait for playback, (timeoutMs) → (bool, error)
	check("sequence_finishes", err == nil && done)

	check("rtp_no_error", full.RtpLevel(0.5) == nil) // Stream an RTP amplitude, (level 0.0–1.0) → error
	time.Sleep(100 * time.Millisecond)
	vdd, err := full.ReadSupplyVoltage() // Read supply voltage, () → (float32 V, error)
	check("supply_voltage_plausible", err == nil && vdd > 2.0 && vdd < 6.0)
	check("stop_no_error", full.Stop() == nil) // Cancel playback, () → error
	playing, err = full.IsPlaying()            // Check playback, () → (bool, error)
	check("stop_clears_go", err == nil && !playing)

	_, err = full.Diagnose() // Run actuator diagnostics, () → (bool, error)
	check("diagnose_runs", err == nil)
	st, err := full.ReadStatus() // Read status flags, () → (DRV2605Status, error)
	check("no_overtemp", err == nil && !st.OverTemp)
	check("no_overcurrent", err == nil && !st.OverCurrent)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
