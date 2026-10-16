//go:build tinygo

// SSD1331 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21.
// The picture itself is verified by eye (see the demo example). Prints PASS/FAIL per
// check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	if err := machine.SPI0.Configure(machine.SPIConfig{
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Frequency: 4_000_000,
		Mode:      0,
	}); err != nil {
		fmt.Printf("FAIL spi_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	dc := connection.NewGpioOutputPin(machine.GP20)
	res := connection.NewGpioOutputPin(machine.GP21)
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex)
	if err != nil {
		fmt.Printf("FAIL connection: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

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
