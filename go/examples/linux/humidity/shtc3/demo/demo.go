//go:build linux && !tinygo

// SHTC3 demo — Linux host.
//
// Battery-friendly climate logger: identify the chip, log six readings, compare normal and low-power mode, print the dew point.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x70"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x70) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewSHTC3Full(conn) // Create SHTC3 driver, (connection) → *SHTC3Full
	chk(chip.Init())                    // Soft reset and ID check, () → error

	// --- Identify the chip ---
	// The ID word carries the SHTC3 product code in bits 11 and 5:0.
	id, err := chip.ReadID() // Read the ID word, () → (uint16, error)
	chk(err)
	fmt.Printf("id 0x%04X\n", id)

	// --- Battery-friendly climate logger: 6 readings, 10 s apart ---
	// The sensor sleeps (0.3 uA) between the readings; every read wakes it.
	for i := 0; i < 6; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		fmt.Printf("%.2f C  %.1f %%RH\n", t, rh)
		time.Sleep(10 * time.Second)
	}

	// --- Compare normal and low-power mode ---
	// Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
	for _, low := range []bool{false, true} {
		chip.SetLowPower(low) // Select low-power mode, (on) → void
		start := time.Now()
		t, rh, err := chip.Read()
		chk(err)
		fmt.Printf("low power=%v %v: %.2f C %.1f %%RH\n", low, time.Since(start), t, rh)
	}
	chip.SetLowPower(false)

	// --- Dew point and raw words, then finish asleep ---
	dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	chk(err)
	tr, rr, err := chip.ReadRaw() // Read raw codes, () → (uint16, uint16, error)
	chk(err)
	fmt.Printf("dew point %.2f C raw %d %d\n", dew, tr, rr)
	chk(chip.Sleep()) // Sleep command, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
