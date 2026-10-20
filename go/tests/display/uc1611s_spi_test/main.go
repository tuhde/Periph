//go:build linux && !tinygo

// UC1611s hardware test — Linux host (Raspberry Pi).
//
// The picture itself is verified by eye (see the demo example). Wire SI/SCL/CSB to SPI0,
// CD = GPIO25, RST = GPIO17 (same as the examples).
package main

import (
	"errors"
	"fmt"
	"os"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		fmt.Printf("FAIL setup: %v\n===DONE: 0 passed, 1 failed===\n", err)
		os.Exit(1)
	}
}

func main() {
	dc, err := connection.NewGpioOutputPin(25)
	must(err)
	res, err := connection.NewGpioOutputPin(17)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, nil, connection.WireModeSimplex)
	must(err)
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	lcd, err := display.NewUC1611sSpiFull(conn, 0, 0, false, 0x8F)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	check("geometry_240x128", lcd.Width() == 240 && lcd.Height() == 128)
	lcd.Clear()
	lcd.DrawRect(0, 0, 240, 128, true, false)
	lcd.DrawLine(0, 0, 239, 127, true)
	lcd.DrawRect(20, 20, 40, 30, true, true)
	check("show_accepted", lcd.Show() == nil && lcd.OK())
	ok := lcd.ShowPage(3) == nil &&
		lcd.SetContrast(0x70) == nil &&
		lcd.SetContrast(0x8F) == nil &&
		lcd.SetInvert(true) == nil &&
		lcd.SetInvert(false) == nil &&
		lcd.SetAllOn(true) == nil &&
		lcd.SetAllOn(false) == nil &&
		lcd.SetFlip(false, true) == nil &&
		lcd.SetFlip(true, false) == nil &&
		lcd.SetFixedLines(2) == nil &&
		lcd.SetScrollLine(8) == nil &&
		lcd.SetScrollLine(0) == nil &&
		lcd.SetFixedLines(0) == nil &&
		lcd.SetBiasRatio(2) == nil &&
		lcd.SetTemperatureCompensation(1) == nil &&
		lcd.SetLineRate(3) == nil &&
		lcd.SetPump(true) == nil &&
		lcd.SetPanelLoading(true) == nil &&
		lcd.SetNLineInversion(0, false) == nil &&
		lcd.SetIsolationClock(1, 0) == nil &&
		lcd.SetPartialDisplay(true, 32, 95) == nil &&
		lcd.SetPartialDisplay(false, 0, -1) == nil &&
		lcd.Show() == nil &&
		lcd.SetDisplayPattern(1) == nil &&
		lcd.SetDisplayPattern(0) == nil
	check("commands_accepted", ok)
	check("sleep_wake_reset_accepted", lcd.Sleep() == nil && lcd.Wake() == nil && lcd.Reset() == nil && lcd.Show() == nil)
	_, err = lcd.ReadStatus()
	check("read_status_unsupported", errors.Is(err, display.ErrUC1611sUnsupported))
	_, err = lcd.ReadRAM(0, 0, 4)
	check("read_ram_unsupported", errors.Is(err, display.ErrUC1611sUnsupported))
	check("no_retries_or_errors", lcd.RetryCount() == 0 && lcd.ErrorCount() == 0)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
