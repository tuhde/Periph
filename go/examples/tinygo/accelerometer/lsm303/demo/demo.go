//go:build tinygo

// LSM303 demo example — TinyGo (Raspberry Pi Pico W).

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
	accelConn, err := connection.NewI2CConnection(0, 0x19, nil, nil)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(0, 0x1E, nil, nil)
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
			println(hdg, name, "pitch=", pitch, "roll=", roll, "|B|=", field, "T=", temp, "fifo=", level)
			isr, err := chip.PollInterrupt() // Read interrupt status, () → (uint16, error)
			if err != nil {
				panic(err)
			}
			if isr&accelerometer.SourceClick != 0 {
				println("DOUBLE TAP")
			}
			if isr&accelerometer.SourceIA1 != 0 {
				println("FREE FALL")
			}
			last = time.Now()
		}
		time.Sleep(10 * time.Millisecond)
	}
	println("done")

}
