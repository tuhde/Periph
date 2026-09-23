//go:build tinygo

// MCP9808 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

// Exercises every method in the MCP9808 public API. The one-way lock
// methods are shown but left commented out — they cannot be undone without
// a power-on reset.

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.MCP9808I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x18) → *I2CConnection
	defer conn.Close()

	s, err := temperature.NewMCP9808Full(conn) // Create MCP9808 Full driver, (conn) → (*MCP9808Full, error)
	if err != nil {                            // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04
		panic(err)
	}

	t, err := s.ReadTemperature() // Read ambient temperature, () → (float32 °C, error)
	if err != nil {               // masks TA's 3 status bits, decodes 1/16 °C two's complement
		panic(err)
	}
	println("temperature (m°C)", int32(t*1000))

	if err := s.SetResolution(0.25); err != nil { // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → error
		panic(err) // 0.25 °C step converts in ~65 ms instead of 250 ms
	}
	res, err := s.GetResolution() // Read resolution, () → (float32 °C, error)
	if err != nil {               // decodes the RESOLUTION register code
		panic(err)
	}
	println("resolution (m°C)", int32(res*1000))

	if err := s.Shutdown(); err != nil { // Enter Shutdown mode, () → error
		panic(err) // stops conversion; TA keeps its last value
	}
	off, err := s.IsShutdown() // Check Shutdown mode, () → (bool, error)
	if err != nil {            // reads CONFIG.SHDN
		panic(err)
	}
	println("shutdown", off)
	if err := s.Wake(); err != nil { // Leave Shutdown mode, () → error
		panic(err) // resumes continuous conversion
	}
	time.Sleep(100 * time.Millisecond)

	if err := s.SetUpperLimit(30); err != nil { // Set TUPPER, (celsius °C) → error
		panic(err) // rounded to the nearest 0.25 °C step
	}
	if err := s.SetLowerLimit(10); err != nil { // Set TLOWER, (celsius °C) → error
		panic(err) // rounded to the nearest 0.25 °C step
	}
	if err := s.SetCriticalLimit(45); err != nil { // Set TCRIT, (celsius °C) → error
		panic(err) // rounded to the nearest 0.25 °C step
	}
	upper, err := s.GetUpperLimit() // Read TUPPER, () → (float32 °C, error)
	if err != nil {                 // decodes the 0.25 °C two's-complement boundary
		panic(err)
	}
	lower, err := s.GetLowerLimit() // Read TLOWER, () → (float32 °C, error)
	if err != nil {                 // decodes the 0.25 °C two's-complement boundary
		panic(err)
	}
	crit, err := s.GetCriticalLimit() // Read TCRIT, () → (float32 °C, error)
	if err != nil {                   // decodes the 0.25 °C two's-complement boundary
		panic(err)
	}
	println("upper (m°C)", int32(upper*1000))
	println("lower (m°C)", int32(lower*1000))
	println("critical (m°C)", int32(crit*1000))

	if err := s.SetHysteresis(1.5); err != nil { // Set hysteresis, (celsius 0|1.5|3.0|6.0) → error
		panic(err) // applied on the cooling edge of each boundary only
	}
	hyst, err := s.GetHysteresis() // Read hysteresis, () → (float32 °C, error)
	if err != nil {                // decodes CONFIG.THYST
		panic(err)
	}
	println("hysteresis (m°C)", int32(hyst*1000))

	// s.LockCriticalLimit() // Lock TCRIT, () → error
	//                       // irreversible until power-on reset
	// s.LockWindowLimits()  // Lock TUPPER/TLOWER, () → error
	//                       // irreversible until power-on reset
	critLocked, err := s.IsCriticalLimitLocked() // Check TCRIT lock, () → (bool, error)
	if err != nil {                              // reads CONFIG.CRIT_LOCK
		panic(err)
	}
	winLocked, err := s.IsWindowLimitsLocked() // Check TUPPER/TLOWER lock, () → (bool, error)
	if err != nil {                            // reads CONFIG.WIN_LOCK
		panic(err)
	}
	println("crit locked", critLocked)
	println("win locked", winLocked)

	if err := s.ConfigureAlert(temperature.MCP9808AlertAll, temperature.MCP9808AlertInterrupt,
		temperature.MCP9808AlertActiveLow); err != nil { // Configure Alert, (mode, output, polarity) → error
		panic(err) // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
	}
	if err := s.EnableAlert(); err != nil { // Enable Alert output, () → error
		panic(err) // sets CONFIG.ALERT_CNT
	}
	asserted, err := s.IsAlertAsserted() // Check Alert output, () → (bool, error)
	if err != nil {                      // reads the read-only CONFIG.ALERT_STAT
		panic(err)
	}
	println("alert asserted", asserted)

	status, err := s.PollInterrupt() // Read boundary status, () → (uint8 mask, error)
	if err != nil {                  // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
		panic(err)
	}
	println("below lower", status&temperature.MCP9808SourceLower != 0)
	println("above upper", status&temperature.MCP9808SourceUpper != 0)
	println("critical", status&temperature.MCP9808SourceCritical != 0)
	if err := s.ClearInterrupt(); err != nil { // Clear interrupt-mode Alert, () → error
		panic(err) // writes CONFIG.INT_CLEAR=1; no effect in comparator mode
	}

	if err := s.OnInterrupt(func(st uint8) { // Subscribe to Alert, (callback) → error
		println("alert, status mask", st) // falls back to a polling goroutine when no IntPin is wired
	}); err != nil {
		panic(err)
	}
	time.Sleep(5000 * time.Millisecond)
	if err := s.OffInterrupt(); err != nil { // Unsubscribe, () → error
		panic(err) // detaches the edge handler or stops the polling goroutine
	}
	if err := s.DisableAlert(); err != nil { // Disable Alert output, () → error
		panic(err) // clears CONFIG.ALERT_CNT
	}
}
