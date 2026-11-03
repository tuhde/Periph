//go:build linux && !tinygo

// SSD1331 hardware test — Linux host (Raspberry Pi).
//
// The picture itself is verified by eye (see the demo example). Wire DIN/CLK/CS to SPI0,
// DC = GPIO25, RST = GPIO17 (same as the examples).
package main

import (
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

	epd, err := display.NewSSD1331Full(conn)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	epd.Clear(display.SSD1331Blue)
	check("clear_readback", epd.GetPixel(0, 0) == display.SSD1331Blue && epd.GetPixel(95, 63) == display.SSD1331Blue)
	epd.SetPixel(5, 6, display.SSD1331Red)
	check("pixel_readback", epd.GetPixel(5, 6) == display.SSD1331Red)
	epd.DrawRect(10, 10, 20, 10, display.SSD1331Green, true)
	check("draw_rect_fill", epd.GetPixel(15, 15) == display.SSD1331Green)
	epd.DrawLine(0, 0, 95, 63, display.SSD1331White)
	check("draw_line_endpoints", epd.GetPixel(0, 0) == display.SSD1331White && epd.GetPixel(95, 63) == display.SSD1331White)
	ok := epd.Show() == nil &&
		epd.ShowRegion(8, 8, 32, 16) == nil &&
		epd.HwClear(0, 0, 95, 63) == nil &&
		epd.HwRect(10, 10, 40, 30, display.SSD1331Yellow, display.SSD1331Red, true) == nil &&
		epd.HwLine(0, 0, 95, 63, display.SSD1331White) == nil &&
		epd.HwCopy(10, 10, 30, 30, 50, 20) == nil &&
		epd.HwDim(0, 0, 47, 63) == nil &&
		epd.SetContrast(0x80, 0x80, 0x80) == nil &&
		epd.SetMasterCurrent(7) == nil &&
		epd.SetDisplayMode(display.SSD1331ModeInverse) == nil &&
		epd.SetDisplayMode(display.SSD1331ModeNormal) == nil &&
		epd.SetFlip(false, false) == nil &&
		epd.Scroll(1, 0, 64, 0, 0) == nil &&
		epd.ScrollStop() == nil &&
		epd.DisplayOff() == nil &&
		epd.DisplayOn() == nil
	check("commands_accepted", ok)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
