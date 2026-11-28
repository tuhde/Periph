//go:build tinygo

// RRH62000 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then prints the air quality of every sample.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = fmt.Sprint
var _ = time.Second

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x69, nil, nil) // Create I2C connection, (i2c, addr=0x69) → (*I2CConnection)

	chip, err := gas.NewRRH62000Minimal(conn) // Create RRH62000 driver, (connection) → (*RRH62000Minimal, error)
	chk(err)
	chk(chip.Init()) // Wait for start-up, check firmware version, () → error

	for {
		r, err := chip.Read() // Wait for a new sample, () → (RRH62000Measurement {PM1UgM3, PM2_5UgM3, PM10UgM3 µg/m³, TemperatureC °C, HumidityRH %RH, TVOC µg/m³, ECO2Ppm ppm, IAQ}, error)
		if err != nil {
			fmt.Println("read failed:", err)
			continue
		}
		fmt.Printf("PM1 %.1f  PM2.5 %.1f  PM10 %.1f ug/m3  %.2f C  %.2f %%RH  TVOC %d ug/m3  eCO2 %d ppm  IAQ %.2f\n",
			r.PM1UgM3, r.PM2_5UgM3, r.PM10UgM3, r.TemperatureC, r.HumidityRH, r.TVOC, r.ECO2Ppm, r.IAQ)
	}
}
