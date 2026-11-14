//go:build linux && !tinygo

// SHT31 demo example — Linux host.
//
// Humidity logger: repeatability levels, periodic mode, heater test, status and dew point.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x44"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x44) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := humidity.NewSHT31Full(conn) // Create SHT31 driver, (connection) → (*SHT31Full, error)
	chk(err)

	chk(chip.SoftReset())            // Soft reset, () → error
	status, err := chip.ReadStatus() // Read status register, () → (uint16, error)
	chk(err)
	fmt.Printf("status 0x%04X\n", status)

	// --- Single shots at all three repeatability levels ---
	for _, level := range []string{humidity.SHT31RepeatabilityLow, humidity.SHT31RepeatabilityMedium, humidity.SHT31RepeatabilityHigh} {
		chk(chip.SetRepeatability(level)) // Select repeatability, (level) → error
		start := time.Now()
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
		chk(err)
		fmt.Printf("%-6s %.2f C %.1f %%RH (%.1f ms)\n", level, t, rh, float64(time.Since(start))/float64(time.Millisecond))
	}
	chip.SetClockStretching(false)     // Use clock-stretching commands, (on) → void
	rawT, rawRH, err := chip.ReadRaw() // Read raw words, () → (uint16, uint16, error)
	chk(err)
	tc, _ := chip.ReadTemperature() // Read temperature only, () → (float32, error)
	rhp, _ := chip.ReadHumidity()   // Read humidity only, () → (float32, error)
	fmt.Println("raw", rawT, rawRH, "T", tc, "RH", rhp)

	// --- Periodic mode at 2 mps: ten samples ---
	chk(chip.StartPeriodic(2, humidity.SHT31RepeatabilityHigh, false)) // Start periodic mode, (rate, repeatability, art) → error
	for got := 0; got < 10; {
		t, rh, ok, err := chip.Fetch() // Fetch the latest periodic result, () → (float32, float32, bool, error)
		chk(err)
		if !ok {
			time.Sleep(50 * time.Millisecond)
			continue
		}
		got++
		fmt.Printf("periodic %d: %.2f C %.1f %%RH\n", got, t, rh)
	}
	chk(chip.StopPeriodic()) // Stop periodic mode, () → error

	// --- Heater test: 10 s ---
	before, _ := chip.ReadTemperature()
	chk(chip.SetHeater(true)) // Switch the heater, (on) → error
	time.Sleep(10 * time.Second)
	status, err = chip.ReadStatus()
	chk(err)
	fmt.Println("heater status bit:", status&0x2000 != 0)
	after, _ := chip.ReadTemperature()
	fmt.Printf("temperature rise: %.2f K\n", after-before)
	chk(chip.SetHeater(false))
	chk(chip.ClearStatus()) // Clear status flags, () → error
	status, _ = chip.ReadStatus()
	fmt.Printf("status 0x%04X\n", status)
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	chk(err)
	fmt.Printf("dew point %.2f C\n", dp)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
