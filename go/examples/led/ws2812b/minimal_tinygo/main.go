//go:build tinygo

// WS2812B minimal example — TinyGo / Raspberry Pi Pico W.
//
// Wires the WS2812B DIN to GP22. Constructs the driver with
// 8 pixels and cycles through red, green, blue, and off.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	tr := transport.NewNeoPixelTransport(machine.GP22) // Create NeoPixel transport, (pin=GP22) → (*NeoPixelTransport)
	strip, err := led.NewWS2812BMinimal(tr, 8)          // Create WS2812B driver, (transport, n=8) → (*WS2812BMinimal, error)
	if err != nil {
		panic(err)
	}

	if err := strip.Fill(255, 0, 0); err != nil { // Fill strip with red, (r=255, g=0, b=0) → error
		fmt.Printf("fill red: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 255, 0); err != nil { // Fill strip with green, (r=0, g=255, b=0) → error
		fmt.Printf("fill green: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 0, 255); err != nil { // Fill strip with blue, (r=0, g=0, b=255) → error
		fmt.Printf("fill blue: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Off(); err != nil { // Turn off strip, () → error
		fmt.Printf("off: %v\n", err)
	}
}
