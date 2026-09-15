//go:build linux && !tinygo

package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)

	tr, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := pressure.NewBMP384Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	// --- Configure for noise-sensitive altitude logging ---
	// osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
	// coefficient 3 suppresses door-slam / gust spikes without too much step lag.
	// ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
	if err := chip.Configure(4, 1, 2, 0x03); err != nil { // Configure ADC and IIR filter, (osrP 0–5, osrT 0–5, iir 0–7, odrSel 0x00–0x11) → error
		fmt.Fprintln(os.Stderr, "configure:", err)
		os.Exit(2)
	}
	if err := chip.SetMode(pressure.BMP384ModeNormal); err != nil { // Set power mode, (mode) → error
		fmt.Fprintln(os.Stderr, "set_mode:", err)
		os.Exit(2)
	}

	// --- Sample for 30 seconds, logging altitude every 500 ms ---
	// P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
	const seaLevelHPa = float32(1013.25)
	start := time.Now()
	next := start
	rows := 0
	for time.Since(start) < 30*time.Second {
		now := time.Now()
		if !now.Before(next) {
			t, err := chip.Temperature()                // Read temperature, () → (float32 °C, error)
			if err != nil {
				fmt.Fprintln(os.Stderr, "temperature:", err)
				os.Exit(2)
			}
			p, err := chip.Pressure()                   // Read pressure, () → (float32 hPa, error)
			if err != nil {
				fmt.Fprintln(os.Stderr, "pressure:", err)
				os.Exit(2)
			}
			alt := float32(44330.0 * (1.0 - math.Pow(float64(p/seaLevelHPa), 1.0/5.255)))
			fmt.Printf("%.1fs  %.2f hPa  %.1f C  %.1f m\n",
				now.Sub(start).Seconds(), p, t, alt)
			rows++
			next = next.Add(500 * time.Millisecond)
		}
		time.Sleep(50 * time.Millisecond)
	}
	fmt.Printf("Sampled %d rows over 30 s\n", rows)
}
