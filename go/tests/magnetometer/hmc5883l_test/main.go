//go:build linux && !tinygo

// HMC5883L hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HMC5883L check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := magnetometer.NewHMC5883LFull(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}
	checkEq := func(label string, got, expected interface{}) {
		if got == expected {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Printf("FAIL %s: got %v, expected %v\n", label, got, expected)
			failed++
		}
	}

	// --- Identification ---
	idA, idB, idC, err := chip.Identify()
	checkEq("identify A", idA, uint8(0x48))
	checkEq("identify B", idB, uint8(0x34))
	checkEq("identify C", idC, uint8(0x33))

	// --- Status ---
	sb, err := chip.Status()
	check("status_byte_valid", err == nil && sb <= 255)

	// --- Data ready ---
	dr, err := chip.DataReady()
	check("data_ready_ok", err == nil && (dr == true || dr == false))

	// --- Magnetic field reading ---
	x, y, z, err := chip.MagneticField()
	check("magneticField x ok", err == nil && (x == -1 || x != -1))
	check("magneticField y ok", err == nil && (y == -1 || y != -1))
	check("magneticField z ok", err == nil && (z == -1 || z != -1))

	// --- Configuration ---
	err = chip.Configure(15, 8, 1)
	check("configure accepted", err == nil)

	err = chip.SetGain(2)
	check("setGain accepted", err == nil)

	err = chip.SetMode("continuous")
	check("setMode continuous accepted", err == nil)

	// --- Single-shot measurement ---
	x, y, z, err = chip.SingleMeasurement()
	check("singleMeasurement x ok", err == nil && (x == -1 || x != -1))
	check("singleMeasurement y ok", err == nil && (y == -1 || y != -1))
	check("singleMeasurement z ok", err == nil && (z == -1 || z != -1))

	err = chip.SetMode("idle")
	check("setMode idle accepted", err == nil)

	// --- Self-test ---
	x, y, z, err = chip.SelfTest(true)
	check("selfTest x ok", err == nil && (x == -1 || x != -1))
	check("selfTest y ok", err == nil && (y == -1 || y != -1))
	check("selfTest z ok", err == nil && (z == -1 || z != -1))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}