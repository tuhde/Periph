//go:build tinygo

// L3G4200D demo — TinyGo / Raspberry Pi Pico W.
//
// Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10.
// In a loop, wait for the FIFO watermark, drain, compute the mean, and
// alert when any axis exceeds 90 dps (~1.57 rad/s).
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
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

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil) // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)

	// --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
	// 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion
	// but not so noisy that the FIFO drains before the watermark is reached.
	chip, err := gyroscope.NewL3G4200DFull(conn, false) // Create L3G4200D driver, (connection) → (*L3G4200DFull, error)
	if err != nil {
		panic(err)
	}
	err = chip.Configure(gyroscope.L3G4200DODR200Hz, 0, gyroscope.L3G4200DFS500DPS) // Configure chip, (odr=ODR_200Hz, bandwidth=0, full_scale=FS_500DPS) → error
	if err != nil {
		panic(err)
	}
	err = chip.EnableHighpass(0, 4) // Enable high-pass, (mode=0, cutoff=4) → error
	if err != nil {
		panic(err)
	}
	// cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
	err = chip.EnableFIFO(gyroscope.L3G4200DFIFOStream, 10) // Enable FIFO, (mode=FIFO_STREAM=2, watermark=10) → error
	if err != nil {
		panic(err)
	}

	thresholdRadS := float32(90.0 * math.Pi / 180.0)
	alerts := 0

	// --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
	// Stream mode keeps the oldest samples; the FIFO never blocks but the
	// host only acts once per watermark crossing to amortise I²C overhead.
	for i := 0; i < 50; i++ {
		for {
			n, err := chip.FIFOSamples() // Read FIFO count, () → (uint8, error)
			if err != nil {
				panic(err)
			}
			if n >= 10 {
				break
			}
			time.Sleep(5 * time.Millisecond)
		}
		burst, err := chip.ReadFIFO() // Drain FIFO, () → ([][3]float32, error)
		if err != nil {
			panic(err)
		}
		if len(burst) == 0 {
			continue
		}
		var mx, my, mz float32
		for _, s := range burst {
			mx += s[0]
			my += s[1]
			mz += s[2]
		}
		n := float32(len(burst))
		mx /= n
		my /= n
		mz /= n
		if math.Abs(float64(mx)) > float64(thresholdRadS) ||
			math.Abs(float64(my)) > float64(thresholdRadS) ||
			math.Abs(float64(mz)) > float64(thresholdRadS) {
			alerts++
			fmt.Printf("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s\n", mx, my, mz)
		} else {
			fmt.Printf("       X=%.2f Y=%.2f Z=%.2f rad/s\n", mx, my, mz)
		}
		time.Sleep(20 * time.Millisecond)
	}

	fmt.Printf("Total alerts: %d / 50\n", alerts)
}
