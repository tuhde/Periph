//go:build tinygo

// LSM303 demo example — TinyGo / Raspberry Pi Pico W.
//
// Tilt-compensated compass with tap and free-fall detection: configures
// ±2 g / 50 Hz high-resolution accelerometer and ±130 µT / 15 Hz
// magnetometer, arms double-tap-on-Z and free-fall detection with both
// routed (latched) to INT1, puts the FIFO in stream mode with a 16-sample
// watermark, and once a second drains the FIFO, reads the magnetic field,
// and prints the tilt-compensated heading as degrees plus a 16-point
// compass name, pitch/roll, the field magnitude, and the relative
// temperature. Polls the interrupt sources for tap / free-fall events.
// Runs 60 s and exits.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

var compassNames = [16]string{
	"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
	"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
}

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	accelConn := connection.NewI2CConnection(i2c, 0x19, nil, nil) // Create accelerometer I²C connection, (i2c, addr=0x19) → (*I2CConnection)
	magConn := connection.NewI2CConnection(i2c, 0x1E, nil, nil)   // Create magnetometer I²C connection, (i2c, addr=0x1E) → (*I2CConnection)

	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for the compass + event demo ---
	// ±2 g and 50 Hz give adequate resolution and latency for tap and
	// free-fall detection; the magnetometer stays at the ±130 µT default
	// with 15 Hz output.
	if err := chip.SetDataRate(50); err != nil { // Set accelerometer data rate, (rate_hz=50) → error
		panic(err)
	}
	if err := chip.SetMode(2); err != nil { // Select high-resolution mode, (mode=2) → error
		panic(err)
	}
	// 12-bit output, 1 mg/LSB at ±2 g

	// --- Arm double tap on Z and free-fall, both latched on INT1 ---
	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil { // Arm double tap on Z, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → error
		panic(err)
	}
	if err := chip.SetFreeFall(1, 0.35, 60); err != nil { // Arm free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=60) → error
		panic(err)
	}
	if err := chip.SetLatch(1, true); err != nil { // Latch generator 1 interrupts, (generator=1, enabled=true) → error
		panic(err)
	}
	// INT1 stays asserted until the source register is read
	if err := chip.EnableInterrupt(accelerometer.SourceClick, 1); err != nil { // Route click to INT1, (source, pin=1) → error
		panic(err)
	}
	if err := chip.EnableInterrupt(accelerometer.SourceIA1, 1); err != nil { // Route generator 1 to INT1, (source, pin=1) → error
		panic(err)
	}

	// --- FIFO in stream mode, 16-sample watermark ---
	if err := chip.SetFifo(2, 16, false); err != nil { // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false) → error
		panic(err)
	}

	fmt.Printf("%-6s %-8s %-6s %-6s %-8s %-10s %-8s\n",
		"Heading", "Compass", "Pitch", "Roll", "|B| (uT)", "Temp (C)", "FIFO")
	for n := 0; n < 60; n++ {
		// --- Drain the FIFO and average the samples ---
		samples, err := chip.ReadFifo(0) // Drain the FIFO, (count ≤ 0 = all unread) → ([][3]float32 g, error)
		if err != nil {
			panic(err)
		}
		var ax, ay, az float64
		if len(samples) > 0 {
			for _, s := range samples {
				ax += float64(s[0])
				ay += float64(s[1])
				az += float64(s[2])
			}
			ax /= float64(len(samples))
			ay /= float64(len(samples))
			az /= float64(len(samples))
		}

		// --- Tilt-compensated heading and compass name ---
		heading, err := chip.Heading(true) // Compute compass heading, (tilt_compensate=true) → (float64 degrees 0–360, error)
		if err != nil {
			panic(err)
		}
		compass := compassNames[int((int(math.Round(heading/22.5))%16+16)%16)]

		// --- Pitch and roll from the averaged gravity vector ---
		pitch := math.Atan2(-ax, math.Sqrt(ay*ay+az*az)) * 180.0 / math.Pi
		roll := math.Atan2(ay, az) * 180.0 / math.Pi

		// --- Field magnitude and relative temperature ---
		mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic flux density, () → (float32 µT, float32 µT, float32 µT, error)
		if err != nil {
			panic(err)
		}
		magnitude := math.Sqrt(float64(mx*mx + my*my + mz*mz))
		delta, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float32 °C, error)
		if err != nil {
			panic(err)
		}

		// --- Tap / free-fall events ---
		status, err := chip.PollInterrupt() // Poll enabled interrupt sources, () → (uint16 combined status, error)
		if err != nil {
			panic(err)
		}
		events := ""
		if status&accelerometer.SourceClick != 0 {
			events += "DOUBLE TAP "
		}
		if status&accelerometer.SourceIA1 != 0 {
			events += "FREE FALL"
		}

		fmt.Printf("%-8.1f %-8s %-6.1f %-6.1f %-8.1f %-10.3f %-8d %s\n",
			heading, compass, pitch, roll, magnitude, delta, len(samples), events)
		time.Sleep(time.Second)
	}
}
