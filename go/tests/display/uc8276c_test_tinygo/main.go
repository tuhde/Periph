//go:build tinygo

// UC8276C hardware test — TinyGo / Raspberry Pi Pico W.
//
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
// Needs a panel: Show waits for the real refresh (about 17 s). Prints PASS/FAIL per
// check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

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
	busy := connection.NewGpioInputPin(machine.GP22)
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, busy, connection.WireModeSimplex)
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

	epd, err := display.NewUC8276CFull(conn, 400, 300, 30*time.Second)
	check("init_valid", err == nil)
	if err != nil {
		fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
		return
	}
	epd.Clear(display.UC8276CWhite)
	epd.SetPixel(1, 1, display.UC8276CBlack)
	check("pixel_black_readback", epd.GetPixel(1, 1) == display.UC8276CBlack)
	epd.SetPixel(3, 1, display.UC8276CRed)
	check("pixel_red_readback", epd.GetPixel(3, 1) == display.UC8276CRed)
	epd.DrawRect(0, 0, 400, 300, display.UC8276CBlack, false)
	epd.DrawLine(0, 0, 399, 299, display.UC8276CBlack)
	check("drawing_accepted", epd.GetPixel(0, 0) == display.UC8276CBlack)
	// show waits for the real refresh (about 17 s).
	check("show_completes", epd.Show() == nil)
	check("sleep_accepted", epd.Sleep() == nil)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
