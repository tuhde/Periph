//go:build linux && !tinygo

// LPS22DF hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
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

	chip, err := pressure.NewLPS22DFMinimal(conn, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 26000.0 && p <= 126000.0)

	who, err := chip.WhoAmI()
	check("who_am_i", err == nil && who == 0xB4)

	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := pressure.NewLPS22DFFull(conn2, false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.Configure(3, 0, true, 1, true); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	alt, err := full.Altitude(101325.0)
	check("altitude_range", err == nil && alt >= -500.0 && alt <= 10000.0)

	_ = full.SetPressureThreshold(102000.0)
	_ = full.ConfigureInterrupt(false, false, true, false, true, false, false, false)
	src, err := full.InterruptSource()
	check("interrupt_source", err == nil)

	count, _ := full.FifoSampleCount()
	samples := make([]float32, 16)
	n, err := full.ReadFifo(samples)
	check("read_fifo", err == nil && int(n) <= int(count)+1)

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