//go:build linux && !tinygo

// LPS22DF demo example — indoor altimeter, Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	// Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter.
	lps, err := pressure.NewLPS22DFFull(conn, false)
	if err != nil {
		panic(err)
	}
	if err := lps.Configure(4, 0, true, 1, true); err != nil {
		panic(err)
	}

	// 2-second stabilization then capture baseline.
	time.Sleep(2 * time.Second)
	baselineP, _ := lps.Pressure()
	fmt.Printf("Baseline: %.0f Pa\n", baselineP)

	pressures := make([]float32, 30)
	temps := make([]float32, 30)
	deltas := make([]float32, 30)
	for n := 0; n < 30; n++ {
		p, _ := lps.Pressure()
		t, _ := lps.Temperature()
		d, _ := lps.Altitude(baselineP)
		pressures[n] = p
		temps[n] = t
		deltas[n] = d
		fmt.Printf("%ds: %.0f Pa, T=%.2f C, dalt=%.3f m\n", n, p, t, d)
		time.Sleep(time.Second)
	}
	pMin, pMax, pSum := pressures[0], pressures[0], float32(0)
	tMin, tMax, tSum := temps[0], temps[0], float32(0)
	dMin, dMax, dSum := deltas[0], deltas[0], float32(0)
	for i := 0; i < 30; i++ {
		if pressures[i] < pMin {
			pMin = pressures[i]
		}
		if pressures[i] > pMax {
			pMax = pressures[i]
		}
		pSum += pressures[i]
		if temps[i] < tMin {
			tMin = temps[i]
		}
		if temps[i] > tMax {
			tMax = temps[i]
		}
		tSum += temps[i]
		if deltas[i] < dMin {
			dMin = deltas[i]
		}
		if deltas[i] > dMax {
			dMax = deltas[i]
		}
		dSum += deltas[i]
	}
	fmt.Printf("P min=%.0f max=%.0f mean=%.1f Pa\n", pMin, pMax, pSum/30)
	fmt.Printf("T min=%.2f max=%.2f mean=%.2f C\n", tMin, tMax, tSum/30)
	fmt.Printf("dalt min=%.3f max=%.3f mean=%.3f m\n", dMin, dMax, dSum/30)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}