//go:build tinygo

// UC8276C complete example — TinyGo / Raspberry Pi Pico W.
//
// Waveshare 4.2inch e-Paper (B). Each refresh blocks for about 17 s.
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
	busy := connection.NewGpioInputPin(machine.GP22)                                                                                     // Create BUSY_N pin, (pin=GP22) → *GpioInputPin
	conn, err := connection.NewDisplayConnectionHardwareSPI(machine.SPI0, machine.GP17, dc, res, busy, connection.WireModeHalfDuplexPin) // Create display connection, (spi, cs, dc, res, busy, wireMode)
	must(err)
	epd, err := display.NewUC8276CFull(conn, 400, 300, 30*time.Second) // Create UC8276C driver, (conn, width=400, height=300, busyTimeout) → (*UC8276CFull, error)
	must(err)
	epd.Clear(display.UC8276CWhite) // Fill framebuffer, (color)
	// both planes set to white
	epd.SetPixel(5, 5, display.UC8276CBlack) // Set one pixel, (x, y, color)
	// clears the B/W bit for that pixel
	epd.DrawRect(20, 20, 100, 60, display.UC8276CRed, true) // Draw rectangle, (x, y, w, h, color, fill)
	// filled red block
	epd.DrawLine(0, 0, 399, 299, display.UC8276CBlack) // Draw line, (x0, y0, x1, y1, color)
	// Bresenham diagonal
	epd.Blit(150, 20, []byte{0xFF, 0x81, 0x81, 0xFF}, 8, 4, display.UC8276CBlack) // Copy 1-bpp bitmap, (x, y, data, w, h, color)
	// 8 x 4 box, MSB first
	colour := epd.GetPixel(30, 30) // Read framebuffer pixel, (x, y) → uint8
	// RED here
	must(epd.SetMode(display.UC8276CModeKWR)) // Select pixel mode, (mode) → error
	// PSR.KW/R cleared: black/white/red
	must(epd.SetFlip(false, false)) // Mirror output, (horizontal, vertical) → error
	// PSR.SHL / UD at power-on values
	must(epd.SetPower(10.0, 10.0, 10.0, 16.0)) // Set source/gate power, (vdh V, vdl V, vdhr V, vgh V) → error
	// panel-layout PWR: +-10 V sources, +-16 V gates
	must(epd.SetFrameRate(50)) // Set frame rate, (hz 4–200) → error
	// nearest PLL M/N entry (M7 N4)
	must(epd.SetSoftStart(0x17, 0x17, 0x17)) // Set booster soft start, (a, b, c) → error
	// vendor BTST phases
	must(epd.SetInterval(7, 3, 1)) // Set VCOM/data interval, (cdi 0–15, border, polarity) → error
	// keeps DDX = 01 so the framebuffer mapping holds
	must(epd.SetTemperatureSensor(false, 0)) // Select temperature sensor, (external, offset -8..7 °C) → error
	// internal sensor, no offset
	status, err := epd.ReadStatus() // Read FLG, () → (uint8, error)
	must(err)
	// needs DOUT wired (half-duplex)
	temperature, err := epd.ReadTemperature() // Read sensor, () → (int °C, error)
	must(err)
	// signed internal temperature
	_, hasChip, lutRev, err := epd.ReadRevision(false) // Read REV, (longForm) → (chipRev, hasChip, lutRev, error)
	must(err)
	// panel form: hasChip is false
	must(epd.Show()) // Refresh panel, () → error
	// DTM1, DTM2, PON, DRF, POF; takes ~17 s
	epd.DrawRect(40, 100, 80, 40, display.UC8276CBlack, true) // Draw rectangle, (x, y, w, h, color, fill)
	must(epd.ShowRegion(40, 100, 80, 40))                     // Refresh a window, (x, y, w, h) → error
	// sends only the window's rows (RAM saving)
	ready, err := epd.WaitBusy(time.Second) // Wait for BUSY_N high, (timeout) → (bool, error)
	must(err)
	// true when the controller is idle
	must(epd.SendCommand(0x50, []byte{0xD7})) // Send raw command, (command, data) → error
	// rewrites CDI with its default
	must(epd.Sleep()) // Enter deep sleep, () → error
	must(epd.Wake())  // Reset and re-initialise, () → error
	// framebuffer is kept
	fmt.Println("colour", colour, "status", status, "temperature", temperature, "hasChip", hasChip, "lutRev", lutRev, "ready", ready)
	fmt.Println("===DONE: 0 passed, 0 failed===")
	must(conn.Close())
}
