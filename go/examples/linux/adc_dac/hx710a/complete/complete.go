//go:build linux && !tinygo

// HX710A complete example — Linux host.
//
// Exercises every method in the HX710AFull API: raw and averaged
// reads, output rate selection, tare, scale factor, weight reading,
// temperature, and power management.
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
	dout, err := strconv.Atoi(envOr("HX710A_DOUT", "2"))
	if err != nil {
		panic(err)
	}
	pdSck, err := strconv.Atoi(envOr("HX710A_PD_SCK", "3"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewHX711Connection(dout, pdSck, nil) // Create HX711 transport connection, (dout=2, pd_sck=3) → (*HX711Connection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	hx, err := adcdac.NewHX710AFull(conn) // Create HX710A driver, (connection) → (*HX710AFull, error)
	if err != nil {
		panic(err)
	}

	ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "is_ready:", err)
		return
	}
	fmt.Printf("is_ready: %v\n", ready)

	raw, err := hx.ReadRaw() // Read 24-bit differential-input value, () → (int32, error)
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

	if err := hx.SetRate(adcdac.HX710ARate40SPS); err != nil { // Select 40 SPS, (rate=40) → error
		// takes effect after the next read; issues a dummy read to apply
		fmt.Fprintln(os.Stderr, "set_rate 40:", err)
	}
	if err := hx.SetRate(adcdac.HX710ARate10SPS); err != nil { // Select 10 SPS, (rate=10) → error
		// restores default rate
		fmt.Fprintln(os.Stderr, "set_rate 10:", err)
	}

	if err := hx.Tare(5); err != nil { // Capture zero offset, (times=5) → error
		// averages `times` readings and stores as offset
		fmt.Fprintln(os.Stderr, "tare:", err)
	}
	offset := hx.GetOffset() // Get tare offset, () → int32
	fmt.Printf("get_offset: %d\n", offset)

	hx.SetScale(420.0)     // Set scale factor, (factor=420.0) → ()
	scale := hx.GetScale() // Get scale factor, () → float32
	fmt.Printf("get_scale: %.1f\n", scale)

	weight, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
	// returns (read_average - offset) / scale
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_weight:", err)
		return
	}
	fmt.Printf("read_weight: %.1f g\n", weight)

	tempRaw, err := hx.ReadTemperatureRaw() // Read raw on-chip temperature code, () → (int32, error)
	// uncalibrated ADC code (~20.4 LSB/°C), not a °C value
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_temperature_raw:", err)
		return
	}
	fmt.Printf("read_temperature_raw: %d\n", tempRaw)

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
