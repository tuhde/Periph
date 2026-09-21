//go:build tinygo

// ADXL362 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=MOSI, GP16=MISO, GP17=CS) and
// reads 3-axis acceleration in *g* once per second.
package main

import (
	"machine"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	chip, err := accelerometer.NewADXL362Minimal(conn)              // Create ADXL362 driver, (connection) → (*ADXL362Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
		if err != nil {
			println("read:", err.Error())
		} else {
			println("x=", strconv.FormatFloat(float64(x), 'f', 3, 32),
				"y=", strconv.FormatFloat(float64(y), 'f', 3, 32),
				"z=", strconv.FormatFloat(float64(z), 'f', 3, 32))
		}
		time.Sleep(time.Second)
	}
}