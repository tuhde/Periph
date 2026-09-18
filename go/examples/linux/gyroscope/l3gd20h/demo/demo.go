package main

import (
	"fmt"
	"math"
	"os"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus := 1
	if b := os.Getenv("I2C_BUS"); b != "" {
		fmt.Sscanf(b, "%d", &bus)
	}
	addr := 0x6A
	if a := os.Getenv("I2C_ADDR"); a != "" {
		fmt.Sscanf(a, "%x", &addr)
	}

	conn, err := connection.NewI2CConnection(bus, addr, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	gyro, err := gyroscope.NewL3GD20HFull(conn, false)
	if err != nil {
		panic(err)
	}

	// --- Configure for shake detection at 190 Hz, ±500 dps ---
	// 190 Hz ODR provides good temporal resolution for shake detection;
	// ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
	// detecting moderate to strong motion without clipping.
	gyro.Configure(gyroscope.L3GD20HODR190Hz, 0, gyroscope.L3GD20HFS500DPS)

	fmt.Println("L3GD20H shake detector running. Shake the device...")

	for {
		drdy, _ := gyro.DataReady()
		if drdy {
			x, y, z, _ := gyro.AngularRate()
			magnitude := math.Sqrt(float64(x*x + y*y + z*z))
			if magnitude > 1.0 {
				fmt.Printf("SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)\n", magnitude, x, y, z)
			} else {
				fmt.Printf("x=%.3f y=%.3f z=%.3f mag=%.3f\n", x, y, z, magnitude)
			}
		}
	}
}