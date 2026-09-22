//go:build linux && !tinygo

// BMP085 complete example — Linux host.
//
// Exercises every method in the Bmp085Full API: chip ID, oversampling,
// temperature, pressure, altitude, sea-level pressure, and soft reset.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, 0x77, nil, nil) // Create I2C connection, (bus=1, addr=0x77) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBmp085Full(conn) // Create BMP085 driver, (connection) → (*Bmp085Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02x\n", cid)
	// returns 0x55 for BMP085

	oss := chip.Oversampling() // Read OSS, () → uint8 0–3
	fmt.Printf("oss=%d\n", oss)
	chip.SetOversampling(pressure.OssStandard) // Set OSS, (oss 0–3) → error
	// changes conversion time vs resolution trade-off

	t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
	if err != nil {
		panic(err)
	}
	p, err := chip.Pressure() // Read pressure, () → (float64 Pa, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.AltitudeAt(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float64 m, error)
	if err != nil {
		panic(err)
	}
	// uses barometric formula to convert pressure to metres
	slp, err := chip.SeaLevelPressure(alt) // Compute sea-level pressure, (altitude_m) → (float64 Pa, error)
	if err != nil {
		panic(err)
	}
	if err := chip.Reset(); err != nil { // Soft reset chip, () → error
		panic(err)
	}
	// re-reads calibration after reset
	fmt.Printf("T=%.1f C, P=%.1f Pa, alt=%.1f m, slp=%.1f Pa\n", t, p, alt, slp)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}