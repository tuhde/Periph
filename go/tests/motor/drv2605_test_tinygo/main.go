//go:build tinygo

// DRV2605 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV2605I2CAddress, nil, nil)
	defer conn.Close()

	m, err := motor.NewDRV2605Minimal(conn, false) // Create DRV2605 driver, (conn, lra=false) → (*DRV2605Minimal, error)
	if err != nil {
		println("init:", err)
		return
	}
	check("construct_minimal", m != nil)
	check("effect_no_error", m.PlayEffect(motor.DRV2605EffectStrongClick) == nil) // Play a ROM effect, (effectID 1–123) → error
	time.Sleep(500 * time.Millisecond)
	playing, err := m.IsPlaying() // Check playback, () → (bool, error)
	check("effect_finishes", err == nil && !playing)

	full, err := motor.NewDRV2605Full(conn, false) // Create DRV2605 Full driver, (conn, lra=false) → (*DRV2605Full, error)
	if err != nil {
		println("init:", err)
		return
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
	_, err = full.Calibrate(false, 3.0, 3.0, 0) // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → (DRV2605Calibration, error)
	check("calibrate_runs", err == nil)
	playing, err = full.IsPlaying()
	check("calibrate_completes", err == nil && !playing)
	st, err := full.ReadStatus() // Read status flags, () → (DRV2605Status, error)
	check("no_overtemp", err == nil && !st.OverTemp)
	check("no_overcurrent", err == nil && !st.OverCurrent)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
