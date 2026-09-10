//go:build tinygo

// ADXL345 demo — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn, err := connection.NewI2CConnection(0, 0x53, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	accel, err := accelerometer.NewADXL345Minimal(conn, false) // Create ADXL345 driver, (conn, spi=false) → (*ADXL345Minimal, error)
	if err != nil {
		panic(err)
	}

	// --- 50-sample stationary tilt characterization at 10 Hz ---
	// With the sensor flat and the Z axis up, gravity should project entirely
	// onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
	// across X and Y; the total vector magnitude stays near 1 *g*.
	const samples = 50
	const periodMs = 100

	var magMin = float32(math.MaxFloat32)
	var magMax = float32(-math.MaxFloat32)

	for n := 0; n < samples; n++ {
		x, y, z, err := accel.Read() // Read 3-axis acceleration, () → (float32, float32, float32, error) g, g, g
		if err != nil {
			panic(err)
		}
		mag := float32(math.Sqrt(float64(x*x + y*y + z*z)))
		if mag < magMin {
			magMin = mag
		}
		if mag > magMax {
			magMax = mag
		}
		println(n, " x=", x, " y=", y, " z=", z, " |a|=", mag, " g")
		time.Sleep(periodMs * time.Millisecond)
	}

	println("min |a|=", magMin, " g  max |a|=", magMax, " g")
}