//go:build linux && !tinygo

// BME688 demo example — Linux host.
//
// Room air probe with parallel mode. Part 1: ten forced-mode readings (T, RH,
// hPa, Ω, dew point), one per 5 s. Part 2: parallel mode with a 3-step heater
// profile (320, 250 and 180 °C; repeats 1, 2, 1; shared wait 100 ms) for one
// minute, printing the gas resistance of every heater step, then back to sleep.
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

	chip, err := environmental.NewBME688Full(conn) // Create BME688 driver, (connection) → (*BME688Full, error)
	if err != nil {
		panic(err)
	}

	chip.Configure( // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → error
		environmental.BME688OSRSX2,
		environmental.BME688OSRSX16,
		environmental.BME688OSRSX1,
		environmental.BME688ModeSleep,
		environmental.BME688Filter15,
	)
	chip.SetHeater(320, 150) // Configure heater profile 0, (temp_c=320, duration_ms=150) → error

	fmt.Println("--- forced mode ---")
	for n := 0; n < 10; n++ {
		t, p, h, g, err := chip.ReadAll() // Read all sensors in one cycle, () → (float32 °C, float32 hPa, float32 %RH, float32 Ω, error)
		if err != nil {
			panic(err)
		}
		// Magnus dew point, °C
		gm := math.Log(math.Max(float64(h), 0.01)/100.0) + 17.62*float64(t)/(243.12+float64(t))
		dew := 243.12 * gm / (17.62 - gm)
		fmt.Printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C\n", n, t, h, p, g, dew)
		time.Sleep(5 * time.Second)
	}

	fmt.Println("--- parallel mode ---")
	chip.StartParallel([]environmental.BME688Step{{TempC: 320, Repeats: 1}, {TempC: 250, Repeats: 2}, {TempC: 180, Repeats: 1}}, 100) // Start parallel mode, (steps []BME688Step, sharedWaitMs=100) → error
	for n := 0; n < 60; n++ {
		fields, err := chip.ReadParallel() // Read the new data fields, () → ([]BME688Field, error)
		if err != nil {
			panic(err)
		}
		for _, f := range fields {
			fmt.Printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%v, stable=%v)\n",
				f.Step, f.SubIndex, f.Temperature, f.Humidity, f.GasResistance, f.GasValid, f.HeaterStable)
		}
		time.Sleep(time.Second)
	}
	chip.StopParallel() // Back to sleep, () → error

}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
