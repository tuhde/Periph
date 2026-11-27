//go:build tinygo

// RRH46410 minimal example — TinyGo / Raspberry Pi Pico W.
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
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)

	chip, err := gas.NewRRH46410Minimal(conn) // Create RRH46410 driver, (connection) → (*RRH46410Minimal, error)
	chk(err)
	chk(chip.Init()) // Check product ID, start IAQ 2nd Gen mode, () → error

	for {
		r, err := chip.Read() // Wait for the next sample, () → (RRH46410Result {IAQ, TVOCMgM3, EtOHPPM, ECO2PPM, RelativeIAQ, Sample, WarmedUp}, error)
		if err != nil {
			fmt.Println("read failed:", err)
			continue
		}
		fmt.Printf("IAQ %.1f  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  warmed up %v\n", r.IAQ, r.TVOCMgM3, r.EtOHPPM, r.ECO2PPM, r.WarmedUp)
	}
}
