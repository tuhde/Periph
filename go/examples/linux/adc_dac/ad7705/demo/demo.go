//go:build linux && !tinygo

// AD7705 demo example — bridge-pressure measurement (datasheet Applications § Pressure Measurement).
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

const (
	tempCoeff     = 0.05
	tempReference = 1.25
	changeThreshold = 0.001
)

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

	chip, err := adcdac.NewAD7705Full(conn, 2.5, adcdac.MCLK2_4576MHz, nil)             // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nil) → (*AD7705Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure both channels for the bridge-pressure application ---
	if err := chip.Configure(1, adcdac.GAIN128, true, true, 50); err != nil {       // Configure channel 1, (channel=1, gain=GAIN128, bipolar=true, buffered=true, output_rate_hz=50) → error
		panic(err)
	}
	if err := chip.Configure(2, adcdac.GAIN2, true, false, 50); err != nil {        // Configure channel 2, (channel=2, gain=GAIN2, bipolar=true, buffered=false, output_rate_hz=50) → error
		panic(err)
	}

	// --- Self-calibrate both channels before the measurement loop ---
	if err := chip.SelfCalibrate(1); err != nil {                                   // Self-calibrate channel, (channel=1) → error
		panic(err)
	}
	if err := chip.SelfCalibrate(2); err != nil {                                   // Self-calibrate channel, (channel=2) → error
		panic(err)
	}

	var lastPressure *float64
	for {
		pressureRaw, err := chip.ReadVoltageChannel(1)                              // Read voltage, (channel=1) → (float64, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "v1:", err)
			continue
		}
		temp, err := chip.ReadVoltageChannel(2)                                      // Read voltage, (channel=2) → (float64, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "v2:", err)
			continue
		}
		pressure := pressureRaw - tempCoeff*(temp-tempReference)
		needPrint := lastPressure == nil || math.Abs(pressure-*lastPressure) > changeThreshold
		if needPrint {
			fmt.Printf("→ pressure=%.4f V (raw %.4f V, temp %.4f V)\n", pressure, pressureRaw, temp)
			p := pressure
			lastPressure = &p
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
