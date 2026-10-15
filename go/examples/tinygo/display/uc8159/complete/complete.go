//go:build tinygo

// UC8159 complete example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 5.65inch e-Paper (F). Each refresh blocks for about 30 s.
// Wiring: DIN=GP19 (SPI0 TX), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
package main

import (
	"fmt"
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
	dc := connection.NewGpioOutputPin(machine.GP20)                                                                                      // Create D/C# pin, (pin=GP20) → *GpioOutputPin
	res := connection.NewGpioOutputPin(machine.GP21)                                                                                     // Create RES# pin, (pin=GP21) → *GpioOutputPin
	busy := connection.NewGpioInputPin(machine.GP22)                                                                                     // Create BUSY pin, (pin=GP22) → *GpioInputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, busy, connection.WireModeHalfDuplexPin) // Create display connection, (spi, cs, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8159Full(conn, 60*time.Second) // Create UC8159 driver, (conn, busyTimeout) → (*UC8159Full, error)
	must(err)
	epd.Clear(display.UC8159White) // Fill framebuffer, (color)
	// every pixel set to white
	epd.SetPixel(5, 5, display.UC8159Black) // Set one pixel, (x, y, color)
	// colour code 0..7
	epd.DrawRect(20, 20, 200, 120, display.UC8159Red, true) // Draw rectangle, (x, y, w, h, color, fill)
	// filled red block
	epd.DrawRect(240, 20, 200, 120, display.UC8159Blue, false) // Draw rectangle, (x, y, w, h, color, fill)
	// blue outline
	epd.DrawLine(0, 0, 599, 447, display.UC8159Green) // Draw line, (x0, y0, x1, y1, color)
	// Bresenham diagonal
	epd.Blit(460, 20, []byte{0xFF, 0x81, 0x81, 0xFF}, 8, 4, display.UC8159Orange) // Copy 1-bpp bitmap, (x, y, data, w, h, color)
	// 8 x 4 box, MSB first
	colour := epd.GetPixel(30, 30) // Read framebuffer pixel, (x, y) → uint8
	// RED here
	must(epd.SetFlip(false, false)) // Mirror output, (horizontal, vertical) → error
	// PSR.SHL / UD at power-on values
	must(epd.SetFrameRate(0x3C)) // Set PLL frame rate code, (code) → error
	// 50 Hz, the vendor value
	must(epd.SetInterval(7, display.UC8159White, 1)) // Set VCOM/data interval, (cdi 0–15, border, polarity) → error
	// white border, 10 hsync
	must(epd.SetTemperatureSensor(false, 0)) // Select temperature sensor, (external, offset -8..7 °C) → error
	// internal sensor, no offset
	status, err := epd.ReadStatus() // Read FLG, () → (uint8, error)
	must(err)
	// needs DOUT wired (half-duplex)
	temperature, err := epd.ReadTemperature() // Read sensor, () → (int °C, error)
	must(err)
	// signed internal temperature
	must(epd.Show()) // Refresh panel, () → error
	// DTM1, PON, DRF, POF; takes about 30 s
	ready, err := epd.WaitBusy(time.Second) // Wait for BUSY high, (timeout) → (bool, error)
	must(err)
	// true when the controller is idle
	must(epd.SendCommand(0x50, []byte{0x37})) // Send raw command, (command, data) → error
	// rewrites CDI with the vendor value
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(epd.Wake())  // Reset and re-initialise, () → error
	// framebuffer is kept
	fmt.Println("colour", colour, "status", status, "temperature", temperature, "ready", ready)
	fmt.Println("===DONE: 0 passed, 0 failed===")
	must(conn.Close())
}
