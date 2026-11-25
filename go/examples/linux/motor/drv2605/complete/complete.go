//go:build linux && !tinygo

// DRV2605 complete example — Linux host.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, motor.DRV2605I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	h, err := motor.NewDRV2605Full(conn, false) // Create DRV2605 Full driver, (conn, lra=false) → (*DRV2605Full, error)
	must(err)

	id, err := h.ReadDeviceID() // Read DEVICE_ID, () → (uint8, error)
	// bits 7:5 of STATUS; 3 for the DRV2605
	must(err)
	fmt.Println("device id", id)
	must(h.PlayEffect(motor.DRV2605EffectStrongClick)) // Play a ROM effect, (effectID 1–123) → error
	// writes slot 1 and the end marker, then sets GO
	_, err = h.WaitDone(5000) // Wait for playback, (timeoutMs) → (bool, error)
	// polls GO until the sequence finishes
	must(err)
	must(h.PlaySequence([]motor.DRV2605SequenceStep{motor.DRV2605Effect(motor.DRV2605EffectStrongClick), motor.DRV2605Wait(100), motor.DRV2605Effect(motor.DRV2605EffectBuzz)})) // Play a sequence, (items ≤ 8) → error
	// Effect = ROM effect, Wait = delay in 10 ms steps
	_, err = h.WaitDone(5000) // Wait for playback, (timeoutMs) → (bool, error)
	must(err)
	playing, err := h.IsPlaying() // Check playback, () → (bool, error)
	// reads the GO bit
	must(err)
	fmt.Println("playing", playing)
	must(h.Stop()) // Cancel playback, () → error
	// clears GO; also leaves RTP mode

	must(h.SetLibrary(1)) // Select ROM library, (library 0–6) → error
	// 0 empty, 1–5 ERM libraries A–E, 6 LRA library
	must(h.SetActuator(false)) // Select actuator type, (lra) → error
	// sets N_ERM_LRA; recalibrate after changing it
	must(h.SetOpenLoop(true)) // Select open-loop drive, (enabled) → error
	// ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
	must(h.SetBidirectional(true)) // Select bidirectional input, (enabled) → error
	// unidirectional is only valid closed-loop
	must(h.SetRatedVoltage(3.0, 0)) // Set rated voltage, (volts V, lraFreqHz Hz) → error
	// code = V / 21.33 mV for an ERM
	must(h.SetOverdriveClamp(3.0, 0)) // Set overdrive clamp, (volts V, lraFreqHz Hz) → error
	// code = V / 21.96 mV in open loop
	must(h.SetRatedVoltageCode(0x3F)) // Set rated voltage code, (code 0–255) → error
	// raw RATED_VOLTAGE register value
	must(h.SetOverdriveClampCode(0x89)) // Set overdrive clamp code, (code 0–255) → error
	// raw OD_CLAMP register value
	must(h.SetDriveTime(2.0)) // Set drive time, (ms) → error
	// ERM back-EMF sample period (1 ms + 0.2 ms × code)
	must(h.SetFeedback(3, 1, 2)) // Set feedback control, (brakeFactor 0–7, loopGain 0–3, bemfGain 0–3) → error
	// keeps the actuator-type bit
	must(h.SetAdvanced(motor.DefaultDRV2605AdvancedConfig())) // Set CONTROL1–3 fields, (DRV2605AdvancedConfig) → error
	// defaults match the chip's reset values
	must(h.SetTimeOffsets(0, 0, 0, 0)) // Set library time offsets, (overdriveMs, sustainPosMs, sustainNegMs, brakeMs) → error
	// ODT/SPT/SNT/BRT for open-loop ROM playback

	must(h.RtpLevel(0.5)) // Stream an RTP amplitude, (level 0.0–1.0) → error
	// enters RTP mode with signed data; 0.5 → 64
	time.Sleep(500 * time.Millisecond)
	must(h.SetRtpFormat(false)) // Select RTP data format, (unsigned) → error
	// false = signed (default)
	must(h.RtpWrite(0x3F)) // Write a raw RTP byte, (value 0–255) → error
	// 0x3F = half scale in signed bidirectional mode
	time.Sleep(500 * time.Millisecond)
	must(h.Stop()) // Cancel playback, () → error
	// zeroes RTP_INPUT and returns to internal trigger

	must(h.SetMode(motor.DRV2605ModeInternalTrigger)) // Select operating mode, (mode 0–7) → error
	// 0 internal trigger … 7 auto-calibration
	must(h.SetInput(false)) // Select PWM/analog input, (analog) → error
	// N_PWM_ANALOG; used with mode 3
	must(h.SetHiZ(false)) // Set high-impedance outputs, (enabled) → error
	// true floats OUT+/OUT−
	cal, err := h.Calibrate(false, 3.0, 3.0, 0) // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → (DRV2605Calibration, error)
	// takes up to 1.2 s
	must(err)
	fmt.Println("calibrated", cal.OK, cal.Comp, cal.BEMF, cal.BEMFGain)
	stored, err := h.GetCalibration() // Read calibration results, () → (DRV2605Calibration, error)
	// A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
	must(err)
	must(h.SetCalibration(stored.Comp, stored.BEMF, stored.BEMFGain)) // Restore calibration, (comp, bemf, bemfGain) → error
	// avoids recalibrating after a reset
	ok, err := h.Diagnose() // Run actuator diagnostics, () → (bool, error)
	// true if DIAG_RESULT = 0
	must(err)
	fmt.Println("actuator ok", ok)
	must(h.SetAudioToVibe(motor.DefaultDRV2605AudioToVibeConfig())) // Configure audio-to-vibe, (DRV2605AudioToVibeConfig) → error
	// enters audio-to-vibe mode
	must(h.SetMode(motor.DRV2605ModeInternalTrigger)) // Select operating mode, (mode 0–7) → error
	// back to internal trigger

	must(h.PlayEffect(motor.DRV2605EffectBuzz)) // Play a ROM effect, (effectID 1–123) → error
	// a buzz, so the supply and LRA readings are valid
	time.Sleep(200 * time.Millisecond)
	vdd, err := h.ReadSupplyVoltage() // Read supply voltage, () → (float32 V, error)
	// VBAT × 5.6 / 255; valid during playback
	must(err)
	fmt.Println("vdd", vdd, "V")
	hz, valid, err := h.ReadLRAFrequency() // Read LRA frequency, () → (float32 Hz, bool, error)
	// valid is false when LRA_PERIOD is 0
	must(err)
	fmt.Println("lra", hz, valid)
	must(h.Stop())            // Cancel playback, () → error
	st, err := h.ReadStatus() // Read status flags, () → (DRV2605Status, error)
	// DiagFailed, FBTimeout, OverTemp, OverCurrent; clears the latches
	must(err)
	fmt.Println("status", st.DiagFailed, st.FBTimeout, st.OverTemp, st.OverCurrent)
	otp, err := h.OTPProgrammed() // Read OTP status, () → (bool, error)
	// true once the OTP has been burned
	must(err)
	fmt.Println("otp", otp)
	// must(h.ProgramOTP(true))                             // IRREVERSIBLE one-time OTP burn, (confirm) → error
	must(h.Standby()) // Enter software standby, () → error
	// also stops playback
	must(h.Wake())  // Leave software standby, () → error
	must(h.Reset()) // Reset all registers, () → error
	// re-create the driver afterwards
}
