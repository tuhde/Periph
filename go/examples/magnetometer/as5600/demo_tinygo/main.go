//go:build tinygo

// AS5600 demo example — TinyGo / Raspberry Pi Pico W.
//
// Motor feedback monitor: reads the angle 10 times per second, prints
// degrees, raw count, and AGC value. When the AGC drifts outside 64–192
// (in 5 V mode) it prints a magnet distance warning. When STATUS changes
// (magnet removed or reinserted) it prints a notification.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x36) // Create I2C transport, (i2c, addr=0x36) → (*I2CTransport)

	// --- Motor feedback monitor: read angle 10 times per second ---
	// AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
	// In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.
	chip, err := magnetometer.NewAs5600Full(tr) // Create AS5600 driver, (transport) → (*As5600Full, error)
	if err != nil {
		panic(err)
	}

	prevStatus, _ := chip.StatusByte() // Read raw status, () → (uint8, error)

	for n := 0; n < 100; n++ {
		a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
		if err != nil {
			panic(err)
		}
		r, err := chip.RawAngle() // Read raw unscaled angle, () → (uint16 0-4095, error)
		if err != nil {
			panic(err)
		}
		g, err := chip.AGC() // Read AGC value, () → (uint8, error)
		if err != nil {
			panic(err)
		}

		// --- Check for status changes (magnet inserted/removed) ---
		status, _ := chip.StatusByte()
		if status != prevStatus {
			if md, _ := chip.IsMagnetDetected(); !md {
				fmt.Println("[MAGNET REMOVED] MD=0")
			} else {
				mh, _ := chip.IsMagnetTooStrong()
				ml, _ := chip.IsMagnetTooWeak()
				fmt.Printf("[MAGNET DETECTED] MD=1  MH=%v  ML=%v\n", mh, ml)
			}
			prevStatus = status
		}

		// --- AGC health check ---
		if md, _ := chip.IsMagnetDetected(); md {
			tag := "[OK]"
			if g < 64 || g > 192 {
				tag = "[AGC low — magnet weak or too far]"
			}
			fmt.Printf("angle=%.2f deg  raw=%d  agc=%d  %s\n", a, r, g, tag) // Read absolute angle, () → (float64 degrees, error)
		}

		time.Sleep(100 * time.Millisecond)
	}
}
