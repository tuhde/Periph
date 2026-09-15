//go:build linux && !tinygo

package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)

	tr, err := connection.NewI2CConnection(bus, uint8(addr64), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
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

	chip, err := pressure.NewBMP384Minimal(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}
	check("default_osr_p_4", chip.OsrP == 4)
	check("default_osr_t_1", chip.OsrT == 1)
	check("default_iir_2",   chip.Iir  == 2)

	t, err := chip.Temperature()
	check("temperature_in_range", err == nil && t >= -40 && t <= 85)
	p, err := chip.Pressure()
	check("pressure_in_range",    err == nil && p >= 300 && p <= 1250)

	full, err := pressure.NewBMP384Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}
	if _, err := full.IsDataReady(); err == nil {
		check("is_data_ready", true)
	} else {
		check("is_data_ready", false)
	}
	if err := full.Configure(2, 1, 1, 0x04); err == nil {
		check("configure_writes_through",
			full.OsrP == 2 && full.Iir == 1 && full.Odr == 0x04)
	} else {
		check("configure_writes_through", false)
	}
	if frames, err := full.FIFORead(); err == nil {
		check("fifo_read_returns_slice", frames != nil)
	} else {
		check("fifo_read_returns_slice", false)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}
