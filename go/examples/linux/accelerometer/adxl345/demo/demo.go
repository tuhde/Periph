//go:build linux && !tinygo

// ADXL345 demo — Linux host.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
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
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x53"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
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
		fmt.Printf("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g\n", n, x, y, z, mag)
		time.Sleep(periodMs * time.Millisecond)
	}

	fmt.Printf("min |a|=%.3f g  max |a|=%.3f g\n", magMin, magMax)
}