//go:build linux && !tinygo

// MS5607 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the MS5607 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x77"), 0, 8)
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

	chip := pressure.NewMS5607Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("init", chip.Init() == nil)
	check("prom_crc_ok", chip.PROMCRCOK())
	t, p, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -40 && t < 85)
	check("read_pressure_range", p > 30000 && p < 120000)
	ps, err := chip.ReadPressure()
	check("read_pressure_single", err == nil && ps > 30000 && ps < 120000)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -40 && tc < 85)
	d1, d2, err := chip.ReadRaw()
	check("read_raw", err == nil && d1 > 0 && d2 > 0)
	_, pc := chip.Compensate(d1, d2, true)
	check("compensate_matches_read", pc-p < 200 && p-pc < 200)
	c := chip.Coefficients()
	check("coefficients_nonzero", c[0] > 0 && c[5] > 0)
	check("serial_code_range", chip.SerialCode() < 4096)
	_, err = chip.ReadPROM()
	check("read_prom", err == nil)
	for _, osr := range []uint16{256, 512, 1024, 2048, 4096} {
		check("osr_roundtrip", chip.SetOversampling(osr) == nil && chip.Oversampling() == osr)
		v, err := chip.ReadPressure()
		check("osr_read", err == nil && v > 30000 && v < 120000)
	}
	check("start_conversion", chip.StartConversion(pressure.MS5607Temperature, 0) == nil)
	time.Sleep(12 * time.Millisecond)
	adc, err := chip.ReadADC()
	check("non_blocking_adc", err == nil && adc > 0)
	alt, err := chip.Altitude(101325)
	check("altitude_plausible", err == nil && alt > -500 && alt < 6000)
	check("reset", chip.Reset() == nil)
	v, err := chip.ReadPressure()
	check("read_after_reset", err == nil && v > 30000 && v < 120000)

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
