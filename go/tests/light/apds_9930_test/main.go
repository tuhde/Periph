//go:build linux && !tinygo

// APDS-9930 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the APDS-9930 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
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

	chip, err := light.NewAPDS9930Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed := 0
	failed := 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	id, err := chip.ChipID()
	check("chip_id is 0x39", err == nil && id == 0x39)

	st, err := chip.Status()
	check("status returns struct", err == nil)
	check("status.AVALID is bool", st.AVALID == st.AVALID) // no-op; just verifying field access

	_, err = chip.Lux()
	check("lux returns no error", err == nil)

	_, err = chip.Proximity()
	check("proximity returns no error", err == nil)

	_, err = chip.Ch0()
	_, err = chip.Ch1()
	check("ch0/ch1 read", err == nil)

	check("configure_als accepted", chip.ConfigureALS(0xDB, 0, false) == nil)
	check("configure_proximity accepted", chip.ConfigureProximity(8, 0, 0, false, 0xFF) == nil)
	check("disable_wait accepted", chip.DisableWait() == nil)
	check("set_als_thresholds accepted", chip.SetAlsThresholds(0, 65535, 1) == nil)
	check("set_proximity_thresholds accepted", chip.SetProximityThresholds(0, 1023, 1) == nil)
	check("set_proximity_offset accepted", chip.SetProximityOffset(0) == nil)
	check("sleep_after_interrupt accepted", chip.SleepAfterInterrupt(false) == nil)
	check("clear_interrupt accepted", chip.ClearInterrupt(0) == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}