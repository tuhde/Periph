//go:build linux && !tinygo

// ST7036 minimal example — Linux host (Raspberry Pi).
//
// EA DOGM162W-A (2 x 16, 3.3 V). Two lines of text.
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
package main

import (
	"time"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	dc, err := connection.NewGpioOutputPin(25) // Create RS (C/D) pin, (line=25) → (*GpioOutputPin, error)
	must(err)
	res, err := connection.NewGpioOutputPin(17) // Create RESET pin, (line=17) → (*GpioOutputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 1_000_000, dc, res, nil, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy=nil, wireMode)
	must(err)
	lcd, err := display.NewST7036Minimal(conn) // Create ST7036 driver, (conn) → (*ST7036Minimal, error)
	must(err)
	must(lcd.PrintAt(0, 0, "Hello, Periph!")) // Write text, (col, row, text) → error
	must(lcd.PrintAt(0, 1, "ST7036 DOGM162")) // Write text, (col, row, text) → error
	_ = time.Second
	must(conn.Close())
}
