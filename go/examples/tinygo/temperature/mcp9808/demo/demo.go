//go:build tinygo

// MCP9808 demo example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/temperature"
	"github.com/tuhde/Periph/go/periph/connection"
)

// Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
// -5 °C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

const maxAlerts = 10

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, temperature.MCP9808I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x18) → *I2CConnection
	defer conn.Close()

	s, err := temperature.NewMCP9808Full(conn) // Create MCP9808 Full driver, (conn) → (*MCP9808Full, error)
	if err != nil {
		panic(err)
	}

	// --- Trade resolution for faster sampling ---
	// 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
	// so a door opening shows up in the next reading almost immediately.
	if err := s.SetResolution(0.25); err != nil { // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → error
		panic(err)
	}

	// --- Program the healthy window and the door-open threshold ---
	// TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
	// 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
	if err := s.SetLowerLimit(-25); err != nil { // Set TLOWER, (celsius °C) → error
		panic(err)
	}
	if err := s.SetUpperLimit(-15); err != nil { // Set TUPPER, (celsius °C) → error
		panic(err)
	}
	if err := s.SetCriticalLimit(-5); err != nil { // Set TCRIT, (celsius °C) → error
		panic(err)
	}
	if err := s.SetHysteresis(3.0); err != nil { // Set hysteresis, (celsius 0|1.5|3.0|6.0) → error
		panic(err)
	}

	// --- Route every boundary to the Alert pin as a latched interrupt ---
	// Interrupt mode latches each crossing until ClearInterrupt, so a short
	// excursion is never missed between two reads.
	if err := s.ConfigureAlert(temperature.MCP9808AlertAll, temperature.MCP9808AlertInterrupt,
		temperature.MCP9808AlertActiveLow); err != nil { // Configure Alert, (mode, output, polarity) → error
		panic(err)
	}
	if err := s.EnableAlert(); err != nil { // Enable Alert output, () → error
		panic(err)
	}

	// --- Report which boundary tripped, then re-arm ---
	// The status mask is a live read of TA's boundary bits; an empty mask
	// means the temperature has come back inside the healthy window.
	events := make(chan uint8, 4)
	if err := s.OnInterrupt(func(st uint8) { events <- st }); err != nil { // Subscribe to Alert, (callback) → error
		panic(err)
	}
	now, err := s.ReadTemperature() // Read ambient temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	println("monitoring, now (m°C)", int32(now*1000))

	for alerts := 0; alerts < maxAlerts; alerts++ {
		st := <-events
		t, err := s.ReadTemperature() // Read ambient temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}
		switch {
		case st&temperature.MCP9808SourceCritical != 0:
			println("CRITICAL - door open? (m°C)", int32(t*1000))
		case st&temperature.MCP9808SourceUpper != 0:
			println("too warm (m°C)", int32(t*1000))
		case st&temperature.MCP9808SourceLower != 0:
			println("too cold (m°C)", int32(t*1000))
		default:
			println("back in range (m°C)", int32(t*1000))
		}
		if err := s.ClearInterrupt(); err != nil { // Clear interrupt-mode Alert, () → error
			panic(err)
		}
	}

	// --- Shut down cleanly after the demo run ---
	if err := s.DisableAlert(); err != nil { // Disable Alert output, () → error
		panic(err)
	}
	if err := s.OffInterrupt(); err != nil { // Unsubscribe, () → error
		panic(err)
	}
}
