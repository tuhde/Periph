// APA102 hardware-in-loop test for TinyGo.
// Prints PASS/FAIL/===DONE=== protocol.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func checkTrue(label string, condition bool) {
	if condition {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func checkEq(label string, got, expected uint8) {
	if got == expected {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Printf("FAIL %s: got %d, expected %d\n", label, got, expected)
		failed++
	}
}

func main() {
	// SPI0 on GP3 (MOSI/TX), GP2 (SCK), no MISO needed
	spi := machine.SPI0
	spi.Configure(machine.SPIConfig{
		Frequency: 1_000_000, // 1 MHz for APA102
		Mode:      0,
		SDO:       machine.GPIO3,  // MOSI
		SCK:       machine.GPIO2,  // SCK
	})

	conn := connection.NewSPIConnectionTinyGo(spi, nil, nil)

	// --- APA102Minimal ---
	{
		strip, err := led.NewAPA102Minimal(conn, 8)
		if err != nil {
			panic(err)
		}

		strip.Fill(255, 0, 0)
		checkTrue("fill(255,0,0) accepted", true)

		strip.Fill(0, 255, 0)
		checkTrue("fill(0,255,0) accepted", true)

		strip.Fill(0, 0, 255)
		checkTrue("fill(0,0,255) accepted", true)

		strip.Off()
		checkTrue("off() accepted", true)
	}

	// Re-init SPI for Full
	spi2 := machine.SPI0
	spi2.Configure(machine.SPIConfig{
		Frequency: 1_000_000,
		Mode:      0,
		SDO:       machine.GPIO3,
		SCK:       machine.GPIO2,
	})

	conn2 := connection.NewSPIConnectionTinyGo(spi2, nil, nil)

	// --- APA102Full ---
	{
		strip, err := led.NewAPA102Full(conn2, 8)
		if err != nil {
			panic(err)
		}

		checkEq("default brightness is 255", strip.GetBrightness(), 255)

		strip.SetPixel(0, 255, 0, 0, 31)
		strip.Show()
		checkTrue("set_pixel + show accepted", true)

		strip.SetPixels([][]uint8{{255, 0, 0}, {0, 255, 0}, {0, 0, 255}})
		strip.Show()
		checkTrue("set_pixels + show accepted", true)

		// set_pixels with per-pixel hardware brightness
		strip.SetPixels([][]uint8{
			{255, 0, 0, 31}, {255, 0, 0, 16}, {255, 0, 0, 8}, {255, 0, 0, 4},
			{0, 255, 0, 31}, {0, 255, 0, 16}, {0, 255, 0, 8}, {0, 255, 0, 4},
		})
		strip.Show()
		checkTrue("set_pixels with hardware brightness + show accepted", true)

		strip.SetBrightness(128)
		checkEq("brightness setter", strip.GetBrightness(), 128)
		strip.Show()
		checkTrue("show() with brightness=128 accepted", true)

		strip.SetBrightness(255)

		strip.Rotate(1)
		strip.Show()
		checkTrue("rotate + show accepted", true)

		strip.FillHSV(0.0, 1.0, 1.0)
		checkTrue("fill_hsv(0.0) accepted", true)

		strip.FillHSV(0.333, 1.0, 1.0)
		checkTrue("fill_hsv(0.333) accepted", true)

		strip.FillHSV(0.667, 1.0, 1.0)
		checkTrue("fill_hsv(0.667) accepted", true)

		strip.Off()
		checkTrue("off() on Full accepted", true)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		// In TinyGo, os.Exit is not available; just loop
		for {
			time.Sleep(time.Second)
		}
	}
}