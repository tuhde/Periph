//go:build linux && !tinygo

// AHT10 complete example — Linux host.
//
// Exercises every method in the AHT10Full API: status inspection, single
// and combined reads, raw values, status, dew point, and soft reset.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := environmental.NewAHT10Full(conn) // Create AHT10 driver, (connection) → (*AHT10Full, error)
	if err != nil {
		panic(err)
	}

	cal, err := chip.IsCalibrated()
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_calibrated: %v\n", cal) // Check calibration status, () → (bool, error)
	// reads CAL bit from status byte

	busy, err := chip.IsBusy()
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_busy: %v\n", busy) // Check busy status, () → (bool, error)
	// reads BUSY bit from status byte

	t, h, err := chip.Read()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read: T=%.2f C  H=%.2f %%RH\n", t, h) // Trigger measurement, () → (float32 °C, float32 %RH, error)
	// sends 0xAC trigger, waits 80 ms, decodes 6 bytes

	tt, err := chip.ReadTemperature()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_temperature: %.2f C\n", tt) // Read temperature only, () → (float32 °C, error)
	// triggers full measurement, returns temperature_c

	hh, err := chip.ReadHumidity()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_humidity: %.2f %%RH\n", hh) // Read humidity only, () → (float32 %RH, error)
	// triggers full measurement, returns humidity_pct

	rh, rt, err := chip.ReadRaw()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_raw: RH=%d T=%d\n", rh, rt) // Read raw 20-bit values, () → (uint32, uint32, error)
	// same measurement as Read, undecoded

	st, err := chip.ReadStatus()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_status: 0x%02X\n", st) // Read status byte, () → (byte, error)
	// bit 7 BUSY, bits 6:5 MODE, bit 3 CAL

	dew, err := chip.DewPoint()
	if err != nil {
		panic(err)
	}
	fmt.Printf("dew_point: %.2f C\n", dew) // Dew point, () → (float32 °C, error)
	// one fresh measurement, Magnus formula

	if err := chip.SoftReset(); err != nil { // Send soft reset command, () → error
		panic(err)
	}
	// sends 0xBA, waits 20 ms, re-runs the init command
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
