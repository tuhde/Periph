//go:build linux && !tinygo

// BMP180 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the BMP180 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, 0x77)
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

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

	chip, err := pressure.NewBmp180Minimal(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 300.0 && p <= 1100.0)

	tr2, err := transport.NewI2CTransport(bus, 0x77)
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport full:", err)
		os.Exit(2)
	}
	defer tr2.Close()

	full, err := pressure.NewBmp180Full(tr2)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}
	check("default_oss", full.Oversampling() == pressure.OssUlp)
	full.SetOversampling(pressure.OssHighRes)
	check("set_oss", full.Oversampling() == pressure.OssHighRes)

	alt, err := full.AltitudeAt(1013.25)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 9000.0)

	slp, err := full.SeaLevelPressure(0.0)
	check("sea_level_pressure", err == nil && slp >= 900.0 && slp <= 1100.0)

	cid, err := full.ChipID()
	check("chip_id", err == nil && cid == 0x55)

	if err := full.Reset(); err != nil {
		fmt.Fprintln(os.Stderr, "reset:", err)
	}
	check("reset", err == nil)

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
