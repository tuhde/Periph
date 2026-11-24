//go:build linux && !tinygo

// SHTC3 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the SHTC3 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x70"), 0, 8)
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

	chip := humidity.NewSHTC3Full(conn)

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
	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("read_temperature_range", t > -45 && t < 130)
	check("read_humidity_range", rh >= 0 && rh <= 100)
	h, err := chip.ReadHumidity()
	check("read_humidity_single", err == nil && h >= 0 && h <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature_single", err == nil && tc > -45 && tc < 130)
	_, _, err = chip.ReadRaw()
	check("read_raw", err == nil)
	dew, err := chip.DewPoint()
	check("dew_point_below_temperature", err == nil && dew <= tc+1)
	id, err := chip.ReadID()
	check("read_id_product_code", err == nil && id&0x083F == 0x0807)
	chip.SetLowPower(true)
	_, r, err := chip.Read()
	check("low_power_read", err == nil && r >= 0 && r <= 100)
	chip.SetLowPower(false)
	chip.SetSleepBetween(false)
	_, r, err = chip.Read()
	check("read_without_sleep", err == nil && r >= 0 && r <= 100)
	_, r, err = chip.Read()
	check("read_without_sleep_again", err == nil && r >= 0 && r <= 100)
	check("sleep", chip.Sleep() == nil)
	check("wake", chip.Wake() == nil)
	chip.SetSleepBetween(true)
	chip.SetClockStretching(true)
	_, r, err = chip.Read()
	check("read_with_clock_stretching", err == nil && r >= 0 && r <= 100)
	chip.SetClockStretching(false)
	check("soft_reset", chip.SoftReset() == nil)
	_, r, err = chip.Read()
	check("read_after_reset", err == nil && r >= 0 && r <= 100)

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
