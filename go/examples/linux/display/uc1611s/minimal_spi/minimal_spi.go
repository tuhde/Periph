//go:build linux && !tinygo

// UC1611s minimal example (4-wire SPI) — Linux host.
//
// EA DOGXL240-7 (240 x 128, 3.3 V only) on /dev/spidev0.0. Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0),
// CD=GPIO25, RST=GPIO17 (Raspberry Pi), VDD = 3V3; module pins BM0 = D13 = VSS (4-wire SPI). Capacitors: 4 x 4.7 uF/5 V
// (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Minimal use: a border and a diagonal on the 240 x 128 panel.
package main

import (
	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	dc, err := connection.NewGpioOutputPin(25) // Create CD (D/C) pin, (line=25) → (*GpioOutputPin, error)
	if err != nil {
		panic(err)
	}
	res, err := connection.NewGpioOutputPin(17) // Create RST pin, (line=17) → (*GpioOutputPin, error)
	if err != nil {
		panic(err)
	}
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 4_000_000, dc, res, nil, connection.WireModeSimplex) // Create display connection, (bus, device, speedHz, dc, res, busy=nil, wireMode)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lcd, err := display.NewUC1611sSpiMinimal(conn, 0, 0) // Create UC1611s driver, (conn, width=240, height=128) → (*UC1611sMinimal, error)
	if err != nil {
		panic(err)
	}

	lcd.Clear() // Clear framebuffer, ()
	for x := 0; x < 240; x++ {
		lcd.SetPixel(x, 0, true)         // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(x, 127, true)       // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(x, x*127/239, true) // Set pixel, (x 0..239, y 0..127, on)
	}
	for y := 0; y < 128; y++ {
		lcd.SetPixel(0, y, true)   // Set pixel, (x 0..239, y 0..127, on)
		lcd.SetPixel(239, y, true) // Set pixel, (x 0..239, y 0..127, on)
	}
	if err := lcd.Show(); err != nil { // Send framebuffer to the display, () → error
		panic(err)
	}
}
