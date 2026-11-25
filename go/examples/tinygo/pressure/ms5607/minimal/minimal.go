//go:build tinygo

// MS5607 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Reads temperature and pressure every second.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil) // Create I2C connection, (i2c, addr=0x77, ) → (*I2CConnection)

	chip := pressure.NewMS5607Minimal(conn) // Create MS5607 driver, (connection) → *MS5607Minimal
	chk(chip.Init())                        // Reset, read PROM, verify CRC-4, () → error
	for {
		t, p, err := chip.Read() // Read temperature and pressure, () → (float32 °C, float32 Pa, error)
		chk(err)                 // OSR 4096, second-order compensation applied
		fmt.Printf("%.2f C, %.1f Pa\n", t, p)
		time.Sleep(time.Second)
	}
}
