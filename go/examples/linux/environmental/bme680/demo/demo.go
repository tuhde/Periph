//go:build linux && !tinygo

// BME680 demo example — Linux host.
//
// Room air-quality probe. Polls all four sensors every 5 seconds for 60
// ticks. At tick 30 the user is prompted to expose the sensor to a VOC
// source (isopropyl alcohol, marker pen) so gas resistance drops sharply
// and recovers over the remaining ticks. After the run, prints min / max
// / mean for temperature and the clean/min ratio for gas resistance.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x76) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := environmental.NewBME680Full(conn) // Create BME680 driver, (connection) → (*BME680Full, error)
	if err != nil {
		panic(err)
	}

	chip.Configure( // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → error
		environmental.BME680OSRSX2,
		environmental.BME680OSRSX16,
		environmental.BME680OSRSX1,
		environmental.BME680ModeForced,
		environmental.BME680Filter15,
	)
	chip.SetHeater(320, 150) // Configure heater profile 0, (temp_c=320, duration_ms=150) → error

	tMin, tMax := float32(math.MaxFloat32), float32(-math.MaxFloat32)
	var tSum float32
	gMin, gMax := float32(math.MaxFloat32), float32(-math.MaxFloat32)
	gasCount := 0

	for n := 0; n < 60; n++ {
		if n == 30 {
			// --- Half-way: expose sensor to VOC source now ---
			// The user is prompted to crack a marker pen or spray a small
			// amount of isopropyl alcohol near the sensor. Gas resistance
			// will drop sharply (often 50 %+ in 1–2 ticks) and recover
			// over the remaining ticks. Demonstrates raw VOC sensitivity
			// without the closed-source BSEC library.
			fmt.Println("--- Expose sensor to VOC source now (alcohol/marker) ---")
		}
		t, p, h, g, err := chip.ReadAll() // Read all sensors in one cycle, () → (float32 °C, float32 hPa, float32 %RH, float32 Ω, error)
		if err != nil {
			panic(err)
		}
		if t < tMin {
			tMin = t
		}
		if t > tMax {
			tMax = t
		}
		tSum += t
		if !math.IsNaN(float64(g)) && g > 0 {
			if g < gMin {
				gMin = g
			}
			if g > gMax {
				gMax = g
			}
			gasCount++
		}
		fmt.Printf("%2d: %.1f C, %.1f %%, %.1f hPa, %.0f Ohm\n", n, t, h, p, g)
		time.Sleep(5 * time.Second)
	}

	tAvg := tSum / 60.0
	fmt.Printf("T: %.1f/%.1f/%.1f C\n", tMin, tAvg, tMax)
	if gasCount > 0 && gMin > 0 {
		fmt.Printf("VOC response ratio: %.1fx\n", gMax/gMin)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
