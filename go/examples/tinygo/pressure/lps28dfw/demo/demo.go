//go:build tinygo

// LPS28DFW demo example — TinyGo / Raspberry Pi Pico W. Depth/altitude logger.
package main

import (
	"fmt"
	"math"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil)       // Create I2C connection, (i2c, addr=0x5C) → (*I2CConnection)

	// --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
	// 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
	chip, err := pressure.NewLPS28DFWFull(conn)                 // Create LPS28DFW driver, (connection) → (*LPS28DFWFull, error)
	if err != nil {
		panic(err)
	}
	if err := chip.Configure(pressure.LPS28DFWODR25Hz, pressure.LPS28DFWAVG64,
		pressure.LPS28DFWFSMode1, pressure.LPS28DFWLFPFODROver4, true); err != nil {
		panic(err) // Configure chip, (odr=25 Hz, avg=64, fsMode=1, lpfCfg=ODR/4, lpfEn=true) → error
	}

	// --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
	// Sea-level reference uses the ISA standard (1013.25 hPa).
	samples := 0
	for n := 0; n < 60; n++ {
		p, t, err := chip.Read() // Read both values, () → (float32 hPa, float32 °C, error)
		if err != nil {
			panic(err)
		}
		alt := float32(44330.0 * (1.0 - math.Pow(float64(p/1013.25), 1.0/5.255)))
		elapsed := float32(n+1) * 0.5
		fmt.Printf("%.1fs  %.2f hPa  %.2f C  %.1f m\n", elapsed, p, t, alt)
		samples++
		time.Sleep(500 * time.Millisecond)
	}
	fmt.Printf("Total samples: %d\n", samples)
}