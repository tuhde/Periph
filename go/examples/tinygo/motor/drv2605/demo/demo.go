//go:build tinygo

// DRV2605 demo example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV2605I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → *I2CConnection
	defer conn.Close()

	const lra = false                         // set to true for a linear resonant actuator
	h, err := motor.NewDRV2605Full(conn, lra) // Create DRV2605 Full driver, (conn, lra=false) → (*DRV2605Full, error)
	must(err)

	// --- Verify and calibrate the actuator ---
	// Diagnose reports a missing or shorted actuator; Calibrate measures the
	// back-EMF so the closed-loop engine matches this particular motor.
	ok, err := h.Diagnose() // Run actuator diagnostics, () → (bool, error)
	must(err)
	println("actuator ok:", ok)
	var lraFreq float32
	if lra {
		lraFreq = 175
	}
	cal, err := h.Calibrate(lra, 3.0, 3.0, lraFreq) // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → (DRV2605Calibration, error)
	must(err)
	println("calibration:", cal.OK, cal.Comp, cal.BEMF, cal.BEMFGain)

	// --- Play the three click effects ---
	for _, effect := range []uint8{motor.DRV2605EffectStrongClick, motor.DRV2605EffectDoubleClick, motor.DRV2605EffectTripleClick} {
		must(h.PlayEffect(effect)) // Play a ROM effect, (effectID 1–123) → error
		_, err = h.WaitDone(5000)  // Wait for playback, (timeoutMs) → (bool, error)
		must(err)
		time.Sleep(500 * time.Millisecond)
	}

	// --- Heartbeat: two strong clicks 100 ms apart ---
	// The sequencer plays both effects and the delay without host involvement.
	must(h.PlaySequence([]motor.DRV2605SequenceStep{motor.DRV2605Effect(motor.DRV2605EffectStrongClick), motor.DRV2605Wait(100), motor.DRV2605Effect(motor.DRV2605EffectStrongClick)})) // Play a sequence, (items ≤ 8) → error
	_, err = h.WaitDone(5000)                                                                                                                                                           // Wait for playback, (timeoutMs) → (bool, error)
	must(err)
	time.Sleep(500 * time.Millisecond)

	// --- Two-second amplitude ramp ---
	// Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
	// while the actuator is driven.
	for step := 0; step <= 20; step++ {
		must(h.RtpLevel(float32(step) / 20)) // Stream an RTP amplitude, (level 0.0–1.0) → error
		time.Sleep(100 * time.Millisecond)
		vdd, err := h.ReadSupplyVoltage() // Read supply voltage, () → (float32 V, error)
		must(err)
		println("vdd", vdd, "V")
		if lra {
			hz, _, err := h.ReadLRAFrequency() // Read LRA frequency, () → (float32 Hz, bool, error)
			must(err)
			println("lra", hz)
		}
	}
	must(h.Stop()) // Cancel playback, () → error

	// --- Report latched faults ---
	st, err := h.ReadStatus() // Read status flags, () → (DRV2605Status, error)
	must(err)
	println("status:", st.DiagFailed, st.FBTimeout, st.OverTemp, st.OverCurrent)
}
