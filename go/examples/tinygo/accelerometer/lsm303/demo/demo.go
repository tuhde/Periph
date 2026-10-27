//go:build tinygo

// LSM303 demo — tilt-compensated compass with tap and free-fall detection (TinyGo, Raspberry Pi Pico W).
//
// Runs for 60 s. Tier-3 blocks mark each logical section.
package main

import (
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

var compassPoints = []string{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
	"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func compassIndex(deg float32) int {
	idx := int(math.Floor((float64(deg)+11.25)/22.5)) % 16
	if idx < 0 {
		idx += 16
	}
	return idx
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	accelConn := connection.NewI2CConnection(machine.I2C0, 0x19, nil, nil) // Create accelerometer connection, (i2c=I2C0, addr=0x19) → *I2CConnection
	magConn := connection.NewI2CConnection(machine.I2C0, 0x1E, nil, nil)   // Create magnetometer connection, (i2c=I2C0, addr=0x1E) → *I2CConnection

	// --- Configure: ±2 g, 50 Hz, high-resolution; magnetometer ±130 µT at 15 Hz ---
	// Moderate rate for steady heading; high-resolution for small tilt changes.
	sensor, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accel_conn, mag_conn) → (*LSM303Full, error)
	must(err)
	must(sensor.SetRange(2))        // Set acceleration range, (range_g=2) → error
	must(sensor.SetDataRate(50))    // Set data rate, (rate_hz=50) → error
	must(sensor.SetMode(2))         // Set accelerometer mode, (mode=2) → error
	must(sensor.SetMagRange(130))   // Set magnetometer range, (range_ut=130) → error µT
	must(sensor.SetMagDataRate(15)) // Set magnetometer rate, (rate_hz=15) → error Hz

	// --- Arm double-tap and free-fall detection on INT1 ---
	// Double tap on Z (mask 0x04) with an 800 mg threshold; free-fall for 60 ms.
	must(sensor.SetClick(0, 0x04, 0.8, 20, 40, 200))                 // Configure click, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → error
	must(sensor.SetFreeFall(1, 0.35, 60))                            // Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=60) → error
	must(sensor.SetLatch(1, true))                                   // Latch generator 1, (generator=1, enabled=true) → error
	must(sensor.EnableInterrupt(accelerometer.LSM303SourceClick, 1)) // Route click, (source=Click, pin=1) → error
	must(sensor.EnableInterrupt(accelerometer.LSM303SourceIA1, 1))   // Route generator 1, (source=IA1, pin=1) → error

	// --- Stream FIFO with a 16-sample watermark, averaged every second ---
	// The FIFO keeps the newest 32 sets, so a 1 s drain never overflows at 50 Hz.
	must(sensor.SetFifo(2, 16, false)) // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false) → error

	for i := 0; i < 60; i++ {
		time.Sleep(time.Second)

		// --- Average the FIFO and read the field ---
		// Averaging over the drained sets reduces noise before the tilt calculation.
		samples, err := sensor.ReadFifo(0) // Drain FIFO, (count=0 all) → ([][3]float32, error) g
		must(err)
		var ax, ay, az float64
		if len(samples) > 0 {
			for _, s := range samples {
				ax += float64(s[0])
				ay += float64(s[1])
				az += float64(s[2])
			}
			n := float64(len(samples))
			ax, ay, az = ax/n, ay/n, az/n
		} else {
			x, y, z, err := sensor.ReadAcceleration() // Read acceleration, () → (float32, float32, float32, error) g
			must(err)
			ax, ay, az = float64(x), float64(y), float64(z)
		}

		// --- Compass, tilt, field magnitude and temperature ---
		// Pitch and roll come from the averaged gravity vector; heading is tilt-compensated.
		heading, err := sensor.Heading(true) // Read compass heading, (tilt_compensate=true) → (float32, error) degrees
		must(err)
		mx, my, mz, err := sensor.ReadMagnetic() // Read magnetic field, () → (float32, float32, float32, error) µT
		must(err)
		temp, err := sensor.ReadTemperatureDelta() // Read temperature delta, () → (float32, error) °C
		must(err)
		roll := math.Atan2(ay, az) * 180 / math.Pi
		pitch := math.Atan2(-ax, math.Sqrt(ay*ay+az*az)) * 180 / math.Pi
		field := math.Sqrt(float64(mx)*float64(mx) + float64(my)*float64(my) + float64(mz)*float64(mz))
		println("heading:", heading, "deg", compassPoints[compassIndex(heading)],
			"pitch:", float32(pitch), "roll:", float32(roll),
			"|B|:", float32(field), "uT temp:", temp, "C")

		// --- Event report ---
		// PollInterrupt reads the enabled sources and clears their latched flags.
		status, err := sensor.PollInterrupt() // Poll interrupts, () → (uint16, error)
		must(err)
		if status&accelerometer.LSM303SourceClick != 0 {
			println("DOUBLE TAP")
		}
		if status&accelerometer.LSM303SourceIA1 != 0 {
			println("FREE FALL")
		}
	}
}
