//go:build linux && !tinygo

package main

import (
	"fmt"
	"os"
	"strconv"

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

	if err := chip.Configure(4, 1, 2, 0x03); err != nil { // Configure ADC and IIR filter, (osrP 0–5, osrT 0–5, iir 0–7, odrSel 0x00–0x11) → error
		fmt.Fprintln(os.Stderr, "configure:", err)
		os.Exit(2)
	}
	if err := chip.SetMode(pressure.BMP384ModeNormal); err != nil { // Set power mode, (mode) → error
		fmt.Fprintln(os.Stderr, "set_mode:", err)
		os.Exit(2)
	}
	ready, err := chip.IsDataReady()                       // Check data-ready flag, () → (bool, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "is_data_ready:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()                          // Read temperature, () → (float32 °C, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "temperature:", err)
		os.Exit(2)
	}
	p, err := chip.Pressure()                             // Read pressure, () → (float32 hPa, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pressure:", err)
		os.Exit(2)
	}
	rp, rt, err := chip.Read()                            // Read both values in one burst, () → (float32, float32, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		os.Exit(2)
	}
	_, _, _ = rp, rt, err
	fp, ft, err := chip.ReadForced()                      // Trigger forced measurement and read, () → (float32, float32, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_forced:", err)
		os.Exit(2)
	}
	_, _, _ = fp, ft, err
	if err := chip.FIFOConfig(true, true, 64, false); err != nil { // Configure FIFO, (pressEn, tempEn, wtm, stopOnFull) → error
		fmt.Fprintln(os.Stderr, "fifo_configure:", err)
		os.Exit(2)
	}
	frames, err := chip.FIFORead()                        // Read and parse FIFO frames, () → ([]BMP384FIFOFrame, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fifo_read:", err)
		os.Exit(2)
	}
	if err := chip.FIFOFlush(); err != nil {              // Flush FIFO contents, () → error
		fmt.Fprintln(os.Stderr, "fifo_flush:", err)
		os.Exit(2)
	}
	alt, err := chip.Altitude(1013.25)                    // Compute altitude, (seaLevelHPa) → (float32 m, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "altitude:", err)
		os.Exit(2)
	}
	if err := chip.Softreset(); err != nil {              // Soft reset chip, () → error
		fmt.Fprintln(os.Stderr, "softreset:", err)
		os.Exit(2)
	}

	fmt.Printf("T=%.1f C, P=%.1f hPa, ready=%v, frames=%d, alt=%.1f m\n",
		t, p, ready, len(frames), alt)
}
