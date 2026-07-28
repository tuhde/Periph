//go:build linux && !tinygo

// HX711 complete example — Linux host.
//
// Exercises every method in the HX711Full API: raw and averaged
// reads, gain selection, tare, scale factor, weight reading, and
// power management.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	dout, err := strconv.Atoi(envOr("HX711_DOUT", "2"))
	if err != nil {
		panic(err)
	}
	pdSck, err := strconv.Atoi(envOr("HX711_PD_SCK", "3"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewHX711Connection(dout, pdSck, nil) // Create HX711 connection, (dout=2, pd_sck=3) → (*HX711Connection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	hx, err := adcdac.NewHX711Full(conn) // Create HX711 driver, (connection) → (*HX711Full, error)
	if err != nil {
		panic(err)
	}

	ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "is_ready:", err)
		return
	}
	fmt.Printf("is_ready: %v\n", ready)

	raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
	// returns a sign-extended value in [-8388608, 8388607]
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_raw:", err)
		return
	}
	fmt.Printf("read_raw: %d\n", raw)

	avg, err := hx.ReadAverage(5) // Read averaged value, (times=5) → (int32, error)
	// average of `times` raw reads
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_average:", err)
		return
	}
	fmt.Printf("read_average(5): %d\n", avg)

	if err := hx.SetGain(adcdac.HX711Gain64); err != nil { // Select gain 64, (gain=64) → error
		// selects Channel A, gain 64; takes effect after the next read
		fmt.Fprintln(os.Stderr, "set_gain 64:", err)
	}
	if err := hx.SetGain(adcdac.HX711Gain32); err != nil { // Select gain 32, (gain=32) → error
		// selects Channel B, gain 32 (fixed)
		fmt.Fprintln(os.Stderr, "set_gain 32:", err)
	}
	if err := hx.SetGain(adcdac.HX711Gain128); err != nil { // Select gain 128, (gain=128) → error
		// resets to Channel A, gain 128 (highest sensitivity)
		fmt.Fprintln(os.Stderr, "set_gain 128:", err)
	}

	if err := hx.Tare(5); err != nil { // Capture zero offset, (times=5) → error
		// averages `times` readings and stores as offset
		fmt.Fprintln(os.Stderr, "tare:", err)
	}
	offset := hx.GetOffset() // Get tare offset, () → int32
	fmt.Printf("get_offset: %d\n", offset)

	hx.SetScale(420.0)                  // Set scale factor, (factor=420.0) → ()
	scale := hx.GetScale()              // Get scale factor, () → float32
	fmt.Printf("get_scale: %.1f\n", scale)

	weight, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
	// returns (read_average - offset) / scale
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_weight:", err)
		return
	}
	fmt.Printf("read_weight: %.1f g\n", weight)

	if err := hx.PowerDown(); err != nil { // Enter power-down, () → error
		// holds PD_SCK HIGH for >60 µs
		fmt.Fprintln(os.Stderr, "power_down:", err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := hx.PowerUp(); err != nil { // Wake chip, () → error
		// drives PD_SCK LOW and discards the first post-reset reading
		fmt.Fprintln(os.Stderr, "power_up:", err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
