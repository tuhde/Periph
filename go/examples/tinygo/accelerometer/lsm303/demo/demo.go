//go:build tinygo

// LSM303 demo example — TinyGo / Raspberry Pi Pico W, a 60-second e-compass and gesture log.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

var directions = [16]string{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
	"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"}

func main() {
	accelConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Println("accelerometer connection:", err)
		return
	}
	magConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Println("magnetometer connection:", err)
		return
	}
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}

	// --- Configure the sensor for a responsive compass ---
	// 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
	// the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
	// precise.
	if err := chip.SetDataRate(50); err != nil {
		fmt.Println("set data rate:", err)
		return
	}
	if err := chip.SetMode(accelerometer.LSM303ModeHighResolution); err != nil {
		fmt.Println("set mode:", err)
		return
	}
	if err := chip.SetMagRange(130); err != nil {
		fmt.Println("set mag range:", err)
		return
	}
	if err := chip.SetMagDataRate(15); err != nil {
		fmt.Println("set mag data rate:", err)
		return
	}

	// --- Arm the gestures ---
	// A double tap on the Z axis and a free-fall event both end up on INT1.
	// The latch keeps the line asserted until the source register is read, so
	// a short tap is never missed between two polling rounds.
	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil {
		fmt.Println("set click:", err)
		return
	}
	if err := chip.SetFreeFall(1, 0.35, 60); err != nil {
		fmt.Println("set free fall:", err)
		return
	}
	if err := chip.SetLatch(1, true); err != nil {
		fmt.Println("set latch:", err)
		return
	}
	if err := chip.EnableInterrupt(accelerometer.LSM303SourceClick, 1); err != nil {
		fmt.Println("enable click interrupt:", err)
		return
	}
	if err := chip.EnableInterrupt(accelerometer.LSM303SourceIA1, 1); err != nil {
		fmt.Println("enable ia1 interrupt:", err)
		return
	}

	// --- Collect the samples in the FIFO ---
	// Stream mode keeps the newest 32 sample sets; the watermark interrupts as
	// soon as 16 of them have piled up.
	if err := chip.SetFifo(accelerometer.LSM303FifoStream, 16, false); err != nil {
		fmt.Println("set fifo:", err)
		return
	}

	for round := 0; round < 60; round++ {
		// --- Average the samples the FIFO collected ---
		// Averaging over the whole burst cancels most of the vibration noise a
		// handheld compass would otherwise show.
		var ax, ay, az float32
		samples, err := chip.ReadFifo(8)
		if err != nil {
			fmt.Println("read fifo:", err)
			return
		}
		if len(samples) > 0 {
			for _, s := range samples {
				ax += s[0]
				ay += s[1]
				az += s[2]
			}
			n := float32(len(samples))
			ax /= n
			ay /= n
			az /= n
		} else {
			ax, ay, az, err = chip.ReadAcceleration()
			if err != nil {
				fmt.Println("read acceleration:", err)
				return
			}
		}

		// --- Compute the heading and the tilt ---
		heading, err := chip.Heading(true)
		if err != nil {
			fmt.Println("heading:", err)
			return
		}
		roll := float32(math.Atan2(float64(ay), float64(az))) * 180 / math.Pi
		pitch := float32(math.Atan2(float64(-ax), math.Sqrt(float64(ay*ay+az*az)))) * 180 / math.Pi
		mx, my, mz, err := chip.ReadMagnetic()
		if err != nil {
			fmt.Println("read magnetic:", err)
			return
		}
		magnitude := float32(math.Sqrt(float64(mx*mx + my*my + mz*mz)))
		temp, err := chip.ReadTemperatureDelta()
		if err != nil {
			fmt.Println("read temperature:", err)
			return
		}
		index := int((heading+11.25)/22.5) % 16
		if index < 0 {
			index += 16
		}

		fmt.Printf("heading %.1f deg (%s)  pitch %.1f deg  roll %.1f deg  field %.1f uT  temp %.1f C\n",
			heading, directions[index], pitch, roll, magnitude, temp)

		// --- Report the gestures ---
		status, err := chip.PollInterrupt()
		if err != nil {
			fmt.Println("poll interrupt:", err)
			return
		}
		if status&accelerometer.LSM303SourceClick != 0 {
			fmt.Println("DOUBLE TAP")
		}
		if status&accelerometer.LSM303SourceIA1 != 0 {
			fmt.Println("FREE FALL")
		}

		time.Sleep(time.Second)
	}
	fmt.Println("done")
}
