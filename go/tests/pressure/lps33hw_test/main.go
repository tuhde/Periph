//go:build linux && !tinygo

// LPS33HW hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the LPS33HW check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
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

	chip, err := pressure.NewLPS33HWMinimal(conn, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}

	t, err := chip.Temperature()
	check("temperature_range", err == nil && t >= -40.0 && t <= 85.0)

	p, err := chip.Pressure()
	check("pressure_range", err == nil && p >= 26000.0 && p <= 126000.0)

	// Re-open the bus for the Full driver.
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := pressure.NewLPS33HWFull(conn2, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.Configure(
		pressure.LPS33HWODR10Hz, 1, 1, pressure.LPS33HWLPFPBWODR20, 0, 0,
	); err == nil {
		check("configure", true)
	} else {
		check("configure", false)
	}

	pOs, tOs, err := full.OneShot()
	check("one_shot_pressure", err == nil && pOs >= 26000.0 && pOs <= 126000.0)
	check("one_shot_temperature", err == nil && tOs >= -40.0 && tOs <= 85.0)

	if _, err := full.Status(); err == nil {
		check("status", true)
	} else {
		check("status", false)
	}

	if _, err := full.InterruptStatus(); err == nil {
		check("interrupt_status", true)
	} else {
		check("interrupt_status", false)
	}

	if err := full.SetPressureOffset(0.0); err == nil {
		check("set_pressure_offset", true)
	} else {
		check("set_pressure_offset", false)
	}

	if err := full.EnableFifo(1, 16); err == nil {
		check("enable_fifo", true)
	} else {
		check("enable_fifo", false)
	}

	if _, err := full.FifoStatus(); err == nil {
		check("fifo_status", true)
	} else {
		check("fifo_status", false)
	}

	if err := full.DisableFifo(); err == nil {
		check("disable_fifo", true)
	} else {
		check("disable_fifo", false)
	}

	if err := full.ResetLpf(); err == nil {
		check("reset_lpf", true)
	} else {
		check("reset_lpf", false)
	}

	if err := full.Reset(); err == nil {
		check("reset", true)
	} else {
		check("reset", false)
	}

	if err := full.Reboot(); err == nil {
		check("reboot", true)
	} else {
		check("reboot", false)
	}

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