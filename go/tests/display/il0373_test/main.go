//go:build linux && !tinygo

// IL0373 hardware test — Linux host (Raspberry Pi).
//
// Needs a panel: Show waits for the real refresh. Wire DIN/CLK/CS to SPI0,
// DC = GPIO25, RST = GPIO17, BUSY = GPIO24 (same as the examples).
package main

import (
	"fmt"
	"os"
	"time"

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
	busy, err := connection.NewGpioInputPin(24)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, busy, connection.WireModeSimplex)
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

	epd, err := display.NewIL0373Full(conn, 104, 212, 30*time.Second)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	epd.Clear(display.IL0373White)
	epd.SetPixel(1, 1, display.IL0373Black)
	check("pixel_black_readback", epd.GetPixel(1, 1) == display.IL0373Black)
	epd.SetPixel(3, 1, display.IL0373White)
	check("pixel_white_readback", epd.GetPixel(3, 1) == display.IL0373White)
	epd.DrawRect(0, 0, 104, 212, display.IL0373Black, false)
	epd.DrawLine(0, 0, 103, 211, display.IL0373Black)
	check("drawing_accepted", epd.GetPixel(0, 0) == display.IL0373Black)
	// show waits for the real refresh.
	check("show_completes", epd.Show() == nil)
	check("sleep_accepted", epd.Sleep() == nil)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
