//go:build tinygo

// ST7036 minimal example — TinyGo / Raspberry Pi Pico W.
//
// EA DOGM162W-A (2 x 16, 3.3 V). Two lines of text.
// Wiring: SI=GP19 (SPI0 TX), SCL=GP18, CSB=GP17, RS=GP20 (C/D), RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
package main

import (
	"machine"
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
	must(machine.SPI0.Configure(machine.SPIConfig{
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Frequency: 1_000_000,
		Mode:      0,
	}))
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                               // Create RS (C/D) pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                              // Create RESET pin, (pin=GP21) → *GpioOutputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, nil, connection.WireModeSimplex) // Create display connection, (spi, cs, dc, res, busy=nil, wireMode)
	must(err)
	lcd, err := display.NewST7036Minimal(conn) // Create ST7036 driver, (conn) → (*ST7036Minimal, error)
	must(err)
	must(lcd.PrintAt(0, 0, "Hello, Periph!")) // Write text, (col, row, text) → error
	must(lcd.PrintAt(0, 1, "ST7036 DOGM162")) // Write text, (col, row, text) → error
	_ = time.Second
	must(conn.Close())
}
