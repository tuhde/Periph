//go:build linux && !tinygo

// HT16K33 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the HT16K33 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
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

	chip, err := display.NewHT16K33Minimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}
	if err := chip.Clear(); err != nil {
		fmt.Fprintln(os.Stderr, "clear:", err)
	}
	check("clear", err == nil)

	bytes := [4]byte{0xED, 0x60, 0xA7, 0xE3}
	if err := chip.WriteRaw(0, bytes[:]); err != nil {
		fmt.Fprintln(os.Stderr, "write_raw:", err)
	}
	check("write_raw", err == nil)

	if err := chip.SetDigit7seg(0, 0xED); err != nil {
		fmt.Fprintln(os.Stderr, "set_digit_7seg:", err)
	}
	check("set_digit_7seg", err == nil)

	check("seven_seg_0", display.HT16K33SevenSeg[0] == 0xED)
	check("seven_seg_9", display.HT16K33SevenSeg[9] == 0xEB)

	// --- Full ---
	conn2, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection full:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	full, err := display.NewHT16K33Full(conn2)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	if err := full.Enable(); err != nil {
		fmt.Fprintln(os.Stderr, "enable:", err)
	}
	if err := full.Disable(); err != nil {
		fmt.Fprintln(os.Stderr, "disable:", err)
	}
	if err := full.Enable(); err != nil {
		fmt.Fprintln(os.Stderr, "enable again:", err)
	}
	check("enable_disable", err == nil)

	if err := full.SetMode(display.HT16K33Backplanes4, display.HT16K33Bias1_3); err != nil {
		fmt.Fprintln(os.Stderr, "set_mode_4:", err)
	}
	if err := full.SetMode(display.HT16K33Backplanes2, display.HT16K33Bias1_2); err != nil {
		fmt.Fprintln(os.Stderr, "set_mode_2:", err)
	}
	if err := full.SetMode(display.HT16K33Backplanes1, display.HT16K33Bias1_3); err != nil {
		fmt.Fprintln(os.Stderr, "set_mode_1:", err)
	}
	check("set_mode", err == nil)

	if err := full.SetBlink(display.HT16K33Blink2Hz, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_blink_2hz:", err)
	}
	if err := full.SetBlink(display.HT16K33BlinkOff, true); err != nil {
		fmt.Fprintln(os.Stderr, "set_blink_off:", err)
	}
	check("set_blink", err == nil)

	if err := full.SetBank(display.HT16K33Bank0, display.HT16K33Bank0); err != nil {
		fmt.Fprintln(os.Stderr, "set_bank_0_0:", err)
	}
	if err := full.SetBank(display.HT16K33Bank1, display.HT16K33Bank1); err != nil {
		fmt.Fprintln(os.Stderr, "set_bank_1_1:", err)
	}
	check("set_bank", err == nil)

	if err := full.DeviceSelect(0); err != nil {
		fmt.Fprintln(os.Stderr, "device_select_0:", err)
	}
	if err := full.DeviceSelect(7); err != nil {
		fmt.Fprintln(os.Stderr, "device_select_7:", err)
	}
	check("device_select", err == nil)

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
