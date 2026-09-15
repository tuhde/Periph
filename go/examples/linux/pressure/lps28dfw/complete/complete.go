//go:build linux && !tinygo

// LPS28DFW complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5C) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewLPS28DFWFull(conn) // Create LPS28DFW driver, (connection) → (*LPS28DFWFull, error)
	if err != nil {
		panic(err)
	}
	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip=0x%02X\n", cid) // returns 0xB4 for LPS28DFW
	if err := chip.Configure(pressure.LPS28DFWODR25Hz, pressure.LPS28DFWAVG64,
		pressure.LPS28DFWFSMode1, pressure.LPS28DFWLFPFODROver4, true); err != nil {
		panic(err) // Configure chip, (odr 0–8, avg 0–7, fsMode 0/1, lpfCfg 0/1, lpfEn bool) → error
	}
	if err := chip.SetThreshold(1050.0, true, true); err != nil {
		panic(err) // Set pressure threshold, (threshold_hpa, high, low) → error
	}
	if err := chip.SetOffset(0.5); err != nil {
		panic(err) // Set one-point calibration, (offset_hpa) → error
	}
	ready, err := chip.IsDataReady() // Check data ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	p, t, err := chip.Read() // Read both values, () → (float32 hPa, float32 °C, error)
	if err != nil {
		panic(err)
	}
	if err := chip.Softreset(); err != nil {
		panic(err) // Soft reset, () → error
	}
	if err := chip.FIFOConfigure(pressure.LPS28DFWFIFOFifo, 16, true); err != nil {
		panic(err) // Configure FIFO, (mode 0–6, wtm 0–127, stopOnWtm bool) → error
	}
	level, err := chip.FIFOLevel() // FIFO unread count, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	samples, err := chip.FIFORead(level) // Drain FIFO, (count) → ([]float32 hPa, error)
	if err != nil {
		panic(err)
	}
	osP, osT, err := chip.ReadOneshot() // One-shot read, () → (float32 hPa, float32 °C, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.Altitude(1013.25) // Compute altitude, (seaLevelHPa=1013.25) → (float32 m, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("ready=%v p=%.2f t=%.2f level=%d os=[%.2f,%.2f] samples=%d alt=%.1f\n",
		ready, p, t, level, osP, osT, len(samples), alt)
}