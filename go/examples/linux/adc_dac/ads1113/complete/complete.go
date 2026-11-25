//go:build linux && !tinygo

// ADS1113 complete example — Linux host.
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS1113I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	d, err := adcdac.NewADS1113Full(conn) // Create ADS1113 Full driver, (conn) → (*ADS1113Full, error)
	// no bus traffic; Config word is rebuilt per conversion
	if err != nil {
		panic(err)
	}
	raw, err := d.ReadRaw() // Read raw code, () → (int16, error)
	// single-shot conversion, signed 16-bit
	if err != nil {
		panic(err)
	}
	v, err := d.ReadVoltage() // Read voltage, () → (float32 V, error)
	// raw × LSB()
	if err != nil {
		panic(err)
	}
	fmt.Printf("raw %v\n", raw)
	fmt.Printf("voltage %v\n", v)
	fs := d.FullScale() // Full scale, () → float32 V
	// fixed ±2.048 V
	lsb := d.LSB() // LSB, () → float32 V
	// FullScale / 32768
	fmt.Printf("full scale %v\n", fs)
	fmt.Printf("lsb %v\n", lsb)
	err = d.SetDataRate(adcdac.ADS1113DR250) // Set data rate, (dr 0-7) → error
	// DR field of the Config word
	if err != nil {
		panic(err)
	}
	sps := d.DataRateSps() // Data rate, () → uint16 SPS
	// samples per second of the current setting
	fmt.Printf("rate SPS %v\n", sps)
	err = d.StartContinuous() // Start continuous, () → error
	// MODE ← 0
	if err != nil {
		panic(err)
	}
	time.Sleep(50 * time.Millisecond)
	craw, err := d.ReadContinuousRaw() // Read continuous raw, () → (int16, error)
	// CONVERSION without touching Config
	if err != nil {
		panic(err)
	}
	cv, err := d.ReadContinuous() // Read continuous, () → (float32 V, error)
	// ReadContinuousRaw() × LSB()
	if err != nil {
		panic(err)
	}
	fmt.Printf("continuous raw %v\n", craw)
	fmt.Printf("continuous %v\n", cv)
	err = d.StopContinuous() // Stop continuous, () → error
	// MODE ← 1
	if err != nil {
		panic(err)
	}
	busy, err := d.IsConverting() // Check conversion, () → (bool, error)
	// CONFIG.OS == 0
	if err != nil {
		panic(err)
	}
	cfg, err := d.ReadConfig() // Read CONFIG, () → (uint16, error)
	// raw 16-bit register
	if err != nil {
		panic(err)
	}
	fmt.Printf("converting %v\n", busy)
	fmt.Printf("config %v\n", cfg)
	err = d.Reset() // Reset, () → error
	// writes the reset values back
	if err != nil {
		panic(err)
	}
}
