//go:build linux && !tinygo

// UC8159 hardware test — Linux host (Raspberry Pi).
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

	epd, err := display.NewUC8159Full(conn, 60*time.Second)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	epd.Clear(display.UC8159White)
	epd.SetPixel(1, 1, display.UC8159Black)
	check("pixel_black_readback", epd.GetPixel(1, 1) == display.UC8159Black)
	epd.SetPixel(3, 1, display.UC8159Red)
	check("pixel_red_readback", epd.GetPixel(3, 1) == display.UC8159Red)
	epd.SetPixel(4, 1, display.UC8159Orange)
	check("pixel_orange_readback", epd.GetPixel(4, 1) == display.UC8159Orange)
	epd.DrawRect(0, 0, 600, 448, display.UC8159Black, false)
	epd.DrawLine(0, 0, 599, 447, display.UC8159Black)
	check("drawing_accepted", epd.GetPixel(0, 0) == display.UC8159Black)
	// show waits for the real refresh.
	check("show_completes", epd.Show() == nil)
	check("sleep_accepted", epd.Sleep() == nil)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
