//go:build linux && !tinygo

// IL0373 complete example — Linux host (Raspberry Pi).
//
// Waveshare 2.13inch e-Paper (D). Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
package main

import (
	"fmt"
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
	dc, err := connection.NewGpioOutputPin(25) // Create D/C# pin, (line=25) → (*GpioOutputPin, error)
	must(err)
	res, err := connection.NewGpioOutputPin(17) // Create RES# pin, (line=17) → (*GpioOutputPin, error)
	must(err)
	busy, err := connection.NewGpioInputPin(24) // Create BUSY pin, (line=24) → (*GpioInputPin, error)
	must(err)
	conn, err := connection.NewDisplayConnectionHardwareSPI(0, 0, 1_000_000, dc, res, busy, connection.WireModeHalfDuplexPin) // Create display connection, (bus, device, speedHz, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewIL0373Full(conn, 104, 212, 30*time.Second) // Create IL0373 driver, (conn, width=104, height=212, busyTimeout) → (*IL0373Full, error)
	must(err)
	epd.Clear(display.IL0373White) // Fill framebuffer, (color)
	// every pixel set to white
	epd.SetPixel(5, 5, display.IL0373Black) // Set one pixel, (x, y, color)
	// clears the bit for that pixel
	epd.DrawRect(20, 20, 60, 40, display.IL0373Black, true) // Draw rectangle, (x, y, w, h, color, fill)
	// filled black block
	epd.DrawLine(0, 0, 103, 211, display.IL0373Black) // Draw line, (x0, y0, x1, y1, color)
	// Bresenham diagonal
	epd.Blit(70, 20, []byte{0xFF, 0x81, 0x81, 0xFF}, 8, 4, display.IL0373Black) // Copy 1-bpp bitmap, (x, y, data, w, h, color)
	// 8 x 4 box, MSB first
	colour := epd.GetPixel(30, 30) // Read framebuffer pixel, (x, y) → uint8
	// BLACK here
	must(epd.SetFlip(false, false)) // Mirror output, (horizontal, vertical) → error
	// PSR.SHL / UD at power-on values
	must(epd.SetPower(14.0, 14.0, 20.0)) // Set source/gate power, (vdh V, vdl V, vgh V) → error
	// PWR: +-14 V sources, +-20 V gates (the power-on values)
	must(epd.SetFrameRate(50)) // Set frame rate, (hz 4–200) → error
	// nearest PLL M/N entry (M7 N4)
	must(epd.SetSoftStart(0x17, 0x17, 0x17)) // Set booster soft start, (a, b, c) → error
	// vendor BTST phases
	must(epd.SetInterval(7, 2, 1)) // Set VCOM/data interval, (cdi 0–15, border, polarity) → error
	// keeps DDX = 01 so the framebuffer mapping holds
	must(epd.SetTemperatureSensor(false, 0)) // Select temperature sensor, (external, offset -8..7 °C) → error
	// internal sensor, no offset
	status, err := epd.ReadStatus() // Read FLG, () → (uint8, error)
	must(err)
	// needs DOUT wired (half-duplex)
	temperature, err := epd.ReadTemperature() // Read sensor, () → (int °C, error)
	must(err)
	// signed internal temperature
	lutRev, err := epd.ReadRevision() // Read REV, () → (uint8, error)
	must(err)
	// LUT revision byte
	must(epd.Show()) // Refresh panel, () → error
	// OLD/NEW planes, DRF, border floating, POF
	epd.DrawRect(16, 100, 64, 40, display.IL0373Black, true) // Draw rectangle, (x, y, w, h, color, fill)
	must(epd.ShowRegion(16, 100, 64, 40))                    // Refresh a window, (x, y, w, h) → error
	// sends only the window's rows (RAM saving)
	ready, err := epd.WaitBusy(time.Second) // Wait for BUSY high, (timeout) → (bool, error)
	must(err)
	// true when the controller is idle
	must(epd.SendCommand(0x50, []byte{0x97})) // Send raw command, (command, data) → error
	// rewrites CDI with the initialisation value
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(epd.Wake())  // Reset and re-initialise, () → error
	// framebuffer is kept
	fmt.Println("colour", colour, "status", status, "temperature", temperature, "lutRev", lutRev, "ready", ready)
	fmt.Println("===DONE: 0 passed, 0 failed===")
	must(conn.Close())
}
