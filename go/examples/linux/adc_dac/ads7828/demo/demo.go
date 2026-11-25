//go:build linux && !tinygo

// ADS7828 demo example — Linux host.
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

	conn, err := connection.NewI2CConnection(bus, adcdac.ADS7828I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x48) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	d, err := adcdac.NewADS7828Full(conn, 0) // Create ADS7828 driver, (conn, vref=0 → internal 2.5 V) → (*ADS7828Full, error)
	if err != nil {
		panic(err)
	}
	if err := d.Init(); err != nil { // Enable internal reference, () → error
		panic(err)
	}

	const divider = 2.0 // inputs sit behind 2:1 dividers (rails up to 5 V)
	channels := []int{0, 1, 2, 3, 4, 5, 6, 7}

	// --- Eight-channel supply monitor ---
	// Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
	// voltages (after the divider ratio) and keep a running minimum/maximum per channel.
	var vmin, vmax [8]float32
	for ch := range vmin {
		vmin[ch], vmax[ch] = 99, 0
	}
	for n := 0; n < 5; n++ {
		raws, err := d.ReadScan(channels) // Scan channels, (channels []int) → ([]uint16, error)
		if err != nil {
			panic(err)
		}
		for ch, r := range raws {
			volts := float32(r) * d.LSB() * divider
			if volts < vmin[ch] {
				vmin[ch] = volts
			}
			if volts > vmax[ch] {
				vmax[ch] = volts
			}
			fmt.Println("ch", ch, "raw", r, "volts", volts)
		}
		time.Sleep(1000 * time.Millisecond)
	}
	fmt.Println("min", vmin, "max", vmax)

	// --- Read a bridge sensor on CH0/CH1 ---
	// The signed differential read does two conversions (normal and inverted
	// polarity) so the sign of the bridge output is preserved.
	mv, err := d.ReadSignedDifferential(0) // Read pair 0 signed, (pair=0–3) → (float32 V, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("bridge mV", mv*1000)

	// --- Second scan against an external 5.0 V reference ---
	// Switching to a 5 V external reference gives full-scale range up to VDD.
	if err := d.SetReference(5.0); err != nil { // Use external reference, (vref V; 0 = internal) → error
		panic(err)
	}
	ext, err := d.ReadScan(channels) // Scan channels, (channels []int) → ([]uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("ext 5 V scan", ext)

	// --- Finish in full power-down ---
	if err := d.PowerDown(); err != nil { // Power down completely, () → error
		panic(err)
	}
}
