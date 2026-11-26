//go:build tinygo

// CCS811 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then prints eCO2 and TVOC for every new sample.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → (*I2CConnection)

	chip, err := gas.NewCCS811Minimal(conn) // Create CCS811 driver, (connection) → (*CCS811Minimal, error)
	chk(err)
	chk(chip.Init()) // Check HW_ID, start application, mode 1, () → error

	for {
		m, err := chip.Read() // Wait for the next sample, () → (CCS811Measurement {ECO2ppm ppm, TVOCppb ppb}, error)
		chk(err)
		fmt.Printf("eCO2 %d ppm, TVOC %d ppb\n", m.ECO2ppm, m.TVOCppb)
	}
}
