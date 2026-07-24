//go:build linux && !tinygo

// AHT21 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AHT21 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := environmental.NewAHT21Full(tr)
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

	cal, err := chip.IsCalibrated()
	check("is_calibrated", err == nil && cal)

	busy, err := chip.IsBusy()
	check("not_busy_at_idle", err == nil && !busy)

	t, h, err := chip.Read()
	check("read_temperature_range", err == nil && t >= -40 && t <= 120)
	check("read_humidity_range", err == nil && h >= 0 && h <= 100)

	tt, err := chip.ReadTemperature()
	check("read_temperature_only", err == nil && tt >= -40 && tt <= 120)

	hh, err := chip.ReadHumidity()
	check("read_humidity_only", err == nil && hh >= 0 && hh <= 100)

	tc, hc, crcOk, err := chip.ReadWithCrc()
	check("crc_ok", err == nil && crcOk)
	check("crc_temperature_range", err == nil && tc >= -40 && tc <= 120)
	check("crc_humidity_range", err == nil && hc >= 0 && hc <= 100)

	if err := chip.SoftReset(); err != nil {
		fmt.Fprintln(os.Stderr, "soft_reset:", err)
	}
	time.Sleep(50 * time.Millisecond)
	cal, err = chip.IsCalibrated()
	check("calibrated_after_reset", err == nil && cal)

	t2, h2, err := chip.Read()
	check("read_after_reset_temperature", err == nil && t2 >= -40 && t2 <= 120)
	check("read_after_reset_humidity", err == nil && h2 >= 0 && h2 <= 100)

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
