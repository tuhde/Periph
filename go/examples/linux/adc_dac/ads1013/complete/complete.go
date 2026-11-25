//go:build linux && !tinygo

// ADS1013 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	adcdac "github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1013I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	adc, err := adcdac.NewADS1013Full(conn) // Create ADS1013 Full driver, (conn) → (*ADS1013Full, error)
	// no bus traffic; fixed ±2.048 V, 1600 SPS
	must(err)

	raw, err := adc.ReadRaw() // Read raw code, () → (int16, error) (−2048…2047)
	// single-shot AIN0 − AIN1, polls OS until idle
	must(err)
	v, err := adc.ReadVoltage() // Read voltage, () → (float32 V, error)
	// raw × 0.001
	must(err)
	fmt.Printf("raw %v\n", raw)
	fmt.Printf("voltage %.4f V\n", v)
	fmt.Printf("full scale %.4f V\n", adc.FullScale()) // Read full-scale range, () → float32 V
	// always 2.048
	fmt.Printf("lsb %.4f mV\n", adc.LSB()*1000) // Read code size, () → float32 V
	// always 0.001

	must(adc.SetDataRate(adcdac.ADS1013DR250)) // Set data rate, (dr 0–6) → error
	// DR250 = 250 SPS
	fmt.Printf("dr code %v\n", adc.GetDataRate()) // Read data-rate code, () → uint8
	// cached DR[2:0]
	fmt.Printf("sps %v\n", adc.DataRateSPS()) // Read data rate, () → uint16 SPS
	// table lookup of the DR code

	must(adc.StartContinuous()) // Start continuous conversion, () → error
	// MODE = 0 at the current data rate
	time.Sleep(20 * time.Millisecond)
	craw, err := adc.ReadContinuousRaw() // Read latest code, () → (int16, error)
	// reads CONVERSION without touching Config
	must(err)
	fmt.Printf("continuous raw %v\n", craw)
	cv, err := adc.ReadContinuous() // Read latest voltage, () → (float32 V, error)
	// raw × LSB()
	must(err)
	fmt.Printf("continuous %.4f V\n", cv)
	must(adc.StopContinuous()) // Stop continuous conversion, () → error
	// MODE = 1, back to single-shot
	busy, err := adc.IsConverting() // Check conversion in progress, () → (bool, error)
	// CONFIG.OS = 0 (single-shot only)
	must(err)
	fmt.Printf("converting %v\n", busy)
	cfg, err := adc.ReadConfig() // Read CONFIG register, () → (uint16, error)
	// raw 16-bit register
	must(err)
	fmt.Printf("config %v\n", cfg)
	must(adc.Reset()) // Reset to power-up defaults, () → error
	// rewrites CONFIG = 0x8583
}
