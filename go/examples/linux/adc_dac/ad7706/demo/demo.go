//go:build linux && !tinygo

// AD7706 demo example — HVAC manifold-pressure monitoring with shared-COMMON 3-channel pseudo-differential inputs.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const filterDpThreshold = 0.001

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 3, 5_000_000, nil, nil)   // Create SPI connection, (bus=0, device=0, mode=3, max_speed=5 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewAD7706Full(conn, 2.5, adcdac.MCLK2_4576MHz, nil)             // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nil) → (*AD7706Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure all three channels for the HVAC manifold-pressure application ---
	// All three pressure transducers are bridge-type with small mV-level output
	// signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
	// The shared-COMMON architecture lets all three bridges share a single return
	// line instead of three fully-differential pairs.
	if err := chip.Configure(1, adcdac.GAIN128, true, true, 50); err != nil {      // Configure channel 1, (channel=1, gain=GAIN128, bipolar=true, buffered=true, output_rate_hz=50) → error
		panic(err)
	}
	if err := chip.Configure(2, adcdac.GAIN128, true, true, 50); err != nil {      // Configure channel 2, (channel=2, gain=GAIN128, bipolar=true, buffered=true, output_rate_hz=50) → error
		panic(err)
	}
	if err := chip.Configure(3, adcdac.GAIN128, true, true, 50); err != nil {      // Configure channel 3, (channel=3, gain=GAIN128, bipolar=true, buffered=true, output_rate_hz=50) → error
		panic(err)
	}

	// --- Self-calibrate all three channels before the measurement loop ---
	if err := chip.SelfCalibrate(1); err != nil {                                   // Self-calibrate channel, (channel=1) → error
		panic(err)
	}
	if err := chip.SelfCalibrate(2); err != nil {                                   // Self-calibrate channel, (channel=2) → error
		panic(err)
	}
	if err := chip.SelfCalibrate(3); err != nil {                                   // Self-calibrate channel, (channel=3) → error
		panic(err)
	}

	// --- Sample continuously and compute filter differential pressure ---
	// Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
	// filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
	var lastFilterDp *float64
	for {
		inlet, err := chip.ReadVoltageChannel(1)                                      // Read voltage, (channel=1) → (float64, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "v1:", err)
			continue
		}
		outlet, err := chip.ReadVoltageChannel(2)                                     // Read voltage, (channel=2) → (float64, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "v2:", err)
			continue
		}
		duct, err := chip.ReadVoltageChannel(3)                                       // Read voltage, (channel=3) → (float64, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "v3:", err)
			continue
		}
		filterDp := inlet - outlet
		needPrint := lastFilterDp == nil || math.Abs(filterDp-*lastFilterDp) > filterDpThreshold
		if needPrint {
			fmt.Printf("→ filter_dp=%.4f V, duct_pressure=%.4f V\n", filterDp, duct)
			f := filterDp
			lastFilterDp = &f
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
