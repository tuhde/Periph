//go:build linux && !tinygo

// MCP3422 demo — Linux host.
//
// Two-channel bridge / thermocouple monitor: CH1 at 16 bit / x1, CH2 at 18 bit / x8,
// then continuous conversion on CH1 at 12 bit / 240 SPS, finishing with a general call reset.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const vdd float32 = 3.3

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.MCP3422I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Full(conn) // Create MCP3422 Full driver, (conn) → (*MCP3422Full, error)
	must(err)

	// --- CH1: thermistor divider at 16 bit / x1 ---
	for i := 0; i < 3; i++ {
		raw, err := adc.ReadRaw(0) // One-shot code, (channel 0–1) → (int32, error)
		must(err)
		v, err := adc.ReadVoltage(0) // One-shot voltage, (channel 0–1) → (float32 V, error)
		must(err)
		fmt.Println("CH1 raw", raw, "=", v, "V")
		time.Sleep(time.Second)
	}

	// --- CH2: bridge sensor at 18 bit / x8 ---
	must(adc.SetResolution(18))           // Select 18 bit / 3.75 SPS, (bits) → error
	must(adc.SetGain(8))                  // Select PGA x8, (gain) → error
	bridge, err := adc.ReadScan([]int{1}) // Scan channels, (channels) → ([]float32 V, error)
	must(err)
	fmt.Println("CH2", bridge[0]*1e6, "uV, LSB", adc.Lsb()/float32(adc.GetGain())*1e6, "uV") // LSB, () → float32 V

	// --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
	must(adc.SetResolution(12))
	must(adc.SetGain(1))
	must(adc.StartContinuous(0)) // Start continuous conversion, (channel) → error
	lo, hi, sum := float32(1e9), float32(-1e9), float32(0)
	t0 := time.Now()
	for i := 0; i < 20; i++ {
		v, err := adc.ReadContinuous() // Next fresh voltage, () → (float32 V, error)
		must(err)
		if v < lo {
			lo = v
		}
		if v > hi {
			hi = v
		}
		sum += v
	}
	elapsed := time.Since(t0).Seconds()
	must(adc.StopContinuous()) // Back to one-shot / standby, () → error
	fmt.Println("min", lo, "max", hi, "mean", sum/20, "V,", 20/elapsed, "samples/s")

	must(adc.GeneralCallReset()) // Reset to power-on defaults, () → error
}
