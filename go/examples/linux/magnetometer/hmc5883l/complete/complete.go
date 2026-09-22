//go:build linux && !tinygo

// HMC5883L complete example — Linux host.
//
// Demonstrates every method in the HMC5883LFull API.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := magnetometer.NewHMC5883LFull(conn) // Create HMC5883L driver, (connection) → (*HMC5883LFull, error)
	if err != nil {
		panic(err)
	}

	// --- Identification ---
	idA, idB, idC, err := chip.Identify() // Read ID registers, () → (uint8, uint8, uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("ID: 0x%02X 0x%02X 0x%02X\n", idA, idB, idC)

	// --- Status ---
	sb, err := chip.Status() // Read raw status, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Status: 0x%02X\n", sb)

	dr, err := chip.DataReady() // Check data ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Data ready: %v\n", dr)

	// --- Magnetic field readings ---
	x, y, z, err := chip.MagneticField() // Read magnetic field, () → (float64 T, float64 T, float64 T, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("X=%.6f T  Y=%.6f T  Z=%.6f T\n", x, y, z)

	// --- Configuration ---
	err = chip.Configure(15, 8, 1) // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → error
	if err != nil {
		panic(err)
	}

	err = chip.SetGain(2) // Set gain, (gain 0-7) → error
	if err != nil {
		panic(err)
	}

	err = chip.SetMode("single") // Set operating mode, ('continuous'|'single'|'idle') → error
	if err != nil {
		panic(err)
	}

	// --- Single-shot measurement ---
	time.Sleep(6 * time.Millisecond)
	x, y, z, err = chip.SingleMeasurement() // Single-shot measurement, () → (float64 T, float64 T, float64 T, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Single: X=%.6f T  Y=%.6f T  Z=%.6f T\n", x, y, z)

	err = chip.SetMode("continuous") // Set operating mode, ('continuous'|'single'|'idle') → error
	if err != nil {
		panic(err)
	}

	// --- Self-test ---
	x, y, z, err = chip.SelfTest(true) // Self-test with positive bias, (positive=bool) → (float64 T, float64 T, float64 T, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Self-test: X=%.6f T  Y=%.6f T  Z=%.6f T\n", x, y, z)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}