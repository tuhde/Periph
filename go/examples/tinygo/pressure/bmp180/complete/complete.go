//go:build tinygo

// BMP180 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the Bmp180Full API on a Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil)     // Create I2C connection, (i2c, addr=0x77) → (*I2CConnection)
	chip, err := pressure.NewBmp180Full(conn)        // Create BMP180 driver, (connection) → (*Bmp180Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02x\n", cid)
	oss := chip.Oversampling() // Read OSS, () → uint8 0–3
	fmt.Printf("oss=%d\n", oss)
	chip.SetOversampling(pressure.OssStandard) // Set OSS, (oss 0–3) → error
	t, err := chip.Temperature() // Read temperature, () → (float64 C, error)
	if err != nil {
		panic(err)
	}
	p, err := chip.Pressure() // Read pressure, () → (float64 hPa, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.AltitudeAt(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float64 m, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("T=%.1f C, P=%.1f hPa, alt=%.1f m\n", t, p, alt)
}
