//go:build linux && !tinygo

// HT16K33 hardware test — Linux host.
//
// Talks to /dev/i2c-N and runs the HT16K33 check sequence. Prints PASS/FAIL
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

	matrix, err := led.NewHT16K33Minimal(conn)
	if err != nil {
		fmt.Println("FAIL init:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("constants", led.HT16K33Commons == 8 && led.HT16K33Rows == 16)
	check("set_pixel", matrix.SetPixel(0, 0, true) == nil && matrix.SetPixel(7, 15, true) == nil)
	check("set_pixel_range", matrix.SetPixel(8, 0, true) != nil && matrix.SetPixel(0, 16, true) != nil)
	check("show", matrix.Show() == nil)
	check("write_raw", matrix.WriteRaw([]byte{0xFF, 0x00}, 0) == nil)
	check("write_raw_overflow", matrix.WriteRaw([]byte{0, 0}, 15) != nil)
	check("set_brightness", matrix.SetBrightness(0) == nil && matrix.SetBrightness(15) == nil)
	check("clear", matrix.Clear() == nil)

	full, err := led.NewHT16K33Full(conn)
	if err != nil {
		fmt.Println("FAIL init full:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	_ = full.SetPixel(3, 4, true)
	_ = full.Show()
	on, _ := full.GetPixel(3, 4)
	off, _ := full.GetPixel(3, 5)
	check("get_pixel", on && !off)
	check("set_blink", full.SetBlink(2) == nil && full.SetBlink(1) == nil && full.SetBlink(0.5) == nil && full.SetBlink(0) == nil)
	check("display_off_on", full.DisplayOff() == nil && full.DisplayOn() == nil)
	check("standby_wake", full.Standby() == nil && full.Wake() == nil)
	check("set_int_output", full.SetIntOutput(true, false) == nil && full.SetIntOutput(true, true) == nil && full.SetIntOutput(false, false) == nil)
	_, err = full.ReadKeys()
	check("read_keys", err == nil)
	_, err = full.KeyPressed()
	check("key_pressed", err == nil)
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
