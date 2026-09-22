// APA102 minimal example — fill entire strip with solid colours.
// go build ./go/examples/linux/led/apa102/minimal/
package main

import (
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(os.Getenv("SPI_BUS"))
	device, _ := strconv.Atoi(os.Getenv("SPI_DEVICE"))

	conn, err := connection.NewSPIConnection(bus, device, 0, 1_000_000, nil, nil)
	if err != nil {
		panic(err)
	}

	strip, err := led.NewAPA102Minimal(conn, 30)
	if err != nil {
		panic(err)
	}

	for {
		strip.Fill(255, 0, 0)                                    // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Fill(0, 255, 0)                                    // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Fill(0, 0, 255)                                    // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Off()                                              // Turn off all pixels, () → error
		time.Sleep(time.Second)
	}
}