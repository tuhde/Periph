//go:build linux && !tinygo

// SAA1064 hardware test — Linux host.
//
// Talks to /dev/i2c-N and runs the SAA1064 check sequence. Prints PASS/FAIL
// per check and ends with the standard ===DONE: ... === line. Exits 0 on full
// pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
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

	display, err := led.NewSAA1064Minimal(conn)
	check("init", err == nil)
	if err != nil {
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("show_digits", display.ShowDigits([4]int{1, 2, 3, 4}) == nil)
	check("show_number", display.ShowNumber(1234) == nil)
	check("write_raw", display.WriteRaw([]byte{0x7F, 0x7F}, 3) == nil)
	check("clear", display.Clear() == nil)

	full, err := led.NewSAA1064Full(conn)
	check("init_full", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		os.Exit(1)
	}
	check("set_mode", full.SetMode(false) == nil && full.SetMode(true) == nil)
	check("blank", full.Blank(true, false) == nil && full.Blank(false, true) == nil && full.Blank(false, false) == nil)
	check("set_current", full.SetCurrent(3) == nil && full.SetCurrent(21) == nil && full.SetCurrent(12) == nil)
	check("segment_test", full.SegmentTest(true) == nil && full.SegmentTest(false) == nil)
	check("set_segment_map", full.SetSegmentMap([8]uint8{0, 1, 2, 3, 4, 5, 6, 7}) == nil)
	check("show_digit", full.ShowDigit(1, 5, true) == nil)
	check("set_decimal_point", full.SetDecimalPoint(1, false) == nil && full.SetDecimalPoint(4, true) == nil)
	_, err = full.ReadStatus()
	check("read_status", err == nil)
	flag, err := full.PowerResetFlag()
	check("power_reset_flag_cleared", err == nil && !flag)
	check("write_control", full.WriteControl(0x47) == nil)
	_ = full.Clear()

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
