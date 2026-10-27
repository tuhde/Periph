//go:build linux && !tinygo

// LSM303 demo example — Linux host.

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

func main() {
	bus, err := strconv.Atoi(os.Getenv("I2C_BUS"))
	if os.Getenv("I2C_BUS") == "" {
		bus = 1
	}
	if err != nil {
		panic(err)
	}
	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		panic(err)
	}

	names := [16]string{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
		"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"}
	if err := chip.SetRange(2); err != nil {
		panic(err)
	} // Set accelerometer range, (rangeG) → error g
	if err := chip.SetMode(accelerometer.LSM303ModeHighResolution); err != nil {
		panic(err)
	} // Set accelerometer mode, (mode) → error
	if err := chip.SetDataRate(50); err != nil {
		panic(err)
	} // Set accelerometer data rate, (rateHz) → error Hz
	if err := chip.SetMagRange(130); err != nil {
		panic(err)
	} // Set magnetometer range, (rangeUT) → error µT
	if err := chip.SetMagDataRate(15); err != nil {
		panic(err)
	} // Set magnetometer data rate, (rateHz) → error Hz

	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil {
		panic(err)
	} // Configure click detection, (...)
	if err := chip.SetFreeFall(1, 0.35, 60); err != nil {
		panic(err)
	} // Configure free-fall, (...)
	if err := chip.SetLatch(1, true); err != nil {
		panic(err)
	} // Latch generator 1, (generator, enabled) → error
	if err := chip.EnableInterrupt(accelerometer.SourceClick, 1); err != nil {
		panic(err)
	} // Route source to INT1, (source, pin) → error
	if err := chip.EnableInterrupt(accelerometer.SourceIA1, 1); err != nil {
		panic(err)
	} // Route source to INT1, (source, pin) → error
	if err := chip.SetFIFO(accelerometer.LSM303FIFOStream, 16, false); err != nil {
		panic(err)
	} // Configure FIFO, (mode, watermark, triggerInt2) → error

	start := time.Now()
	last := start
	for time.Since(start) < 60*time.Second {
		if time.Since(last) >= time.Second {
			level, err := chip.FIFOLevel() // Read FIFO level, () → (int, error)
			if err != nil {
				panic(err)
			}
			samples, err := chip.ReadFIFO(-1) // Drain FIFO, (count) → ([][3]float64, error)
			if err != nil {
				panic(err)
			}
			ax, ay, az, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
			if err != nil {
				panic(err)
			}
			if len(samples) > 0 {
				var sx, sy, sz float64
				for _, s := range samples {
					sx += s[0]
					sy += s[1]
					sz += s[2]
				}
				n := float64(len(samples))
				ax, ay, az = sx/n, sy/n, sz/n
			}
			mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic field, () → (µT, µT, µT, error)
			if err != nil {
				panic(err)
			}
			field := math.Sqrt(mx*mx + my*my + mz*mz)
			pitch := math.Atan2(ax, math.Sqrt(ay*ay+az*az)) * 180 / math.Pi
			roll := math.Atan2(ay, az) * 180 / math.Pi
			hdg, err := chip.Heading(true) // Compute compass heading, (tiltCompensate) → (float64, error) deg
			if err != nil {
				panic(err)
			}
			name := names[int((hdg+11.25)/22.5)%16]
			temp, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float64, error) °C
			if err != nil {
				panic(err)
			}
			fmt.Printf("%5.1f° %s  pitch=%+.1f roll=%+.1f |B|=%.1f µT  T=%+.1f °C  fifo=%d\n", hdg, name, pitch, roll, field, temp, level)
			isr, err := chip.PollInterrupt() // Read interrupt status, () → (uint16, error)
			if err != nil {
				panic(err)
			}
			if isr&accelerometer.SourceClick != 0 {
				fmt.Println("DOUBLE TAP")
			}
			if isr&accelerometer.SourceIA1 != 0 {
				fmt.Println("FREE FALL")
			}
			last = time.Now()
		}
		time.Sleep(10 * time.Millisecond)
	}
	fmt.Println("done")

}
