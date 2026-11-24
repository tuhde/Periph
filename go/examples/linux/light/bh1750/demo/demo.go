//go:build linux && !tinygo

// BH1750 demo — Linux host.
//
// Light meter: all modes, continuous mode, sensitivity, reset and power cycle.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x23"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x23) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := light.NewBH1750Full(conn) // Create BH1750 driver, (connection) → *BH1750Full
	chk(chip.Init())                  // Power on, () → error

	// --- Light meter ---
	// One reading per mode, ten continuous values per second for 5 s (cover and
	// uncover the sensor), the effect of the lowest and highest sensitivity on the
	// raw count and the measurement time, reset and a power cycle.
	for _, m := range []struct {
		name string
		mode light.BH1750Mode
	}{{"H (1 lx)", light.BH1750ModeH}, {"H2 (0.5 lx)", light.BH1750ModeH2}, {"L (4 lx)", light.BH1750ModeL}} {
		lux, err := chip.MeasureOnce(m.mode) // One-time measurement, (mode) → (float32 lx, error)
		chk(err)
		fmt.Printf("%-12s %9.2f lx\n", m.name, lux)
	}

	chk(chip.StartContinuous(light.BH1750ModeH)) // Start continuous mode, (mode) → error
	for i := 0; i < 50; i++ {
		time.Sleep(100 * time.Millisecond)
		lux, err := chip.ReadContinuous() // Read the latest result, () → (float32 lx, error)
		chk(err)
		fmt.Printf("%.1f lx\n", lux)
	}

	for _, factor := range []float32{0.45, 3.68} {
		chk(chip.SetSensitivity(factor)) // Set sensitivity, (factor) → error
		lux, err := chip.MeasureOnce(light.BH1750ModeH)
		chk(err)
		raw, err := chip.ReadRaw()
		chk(err)
		fmt.Printf("x%.2f: MTreg %d, raw %d, %.2f lx, max %.0f ms\n", factor, chip.GetMeasurementTimeRegister(), raw, lux, chip.MeasurementTime(light.BH1750ModeH)*1000)
	}
	chk(chip.SetSensitivity(1.0))

	chk(chip.Reset())     // Clear the data register, () → error
	chk(chip.PowerDown()) // Power down, () → error
	chk(chip.PowerOn())   // Power on, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
