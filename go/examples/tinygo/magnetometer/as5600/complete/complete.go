//go:build tinygo

// AS5600 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the As5600Full API on a Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
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

	conn := connection.NewI2CConnection(i2c, 0x36, nil, nil) // Create I2C connection, (i2c, addr=0x36) → (*I2CConnection)
	chip, err := magnetometer.NewAs5600Full(conn) // Create AS5600 driver, (connection) → (*As5600Full, error)
	if err != nil {
		panic(err)
	}

	md, err := chip.IsMagnetDetected() // Check magnet present, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("magnet_detected:", md)

	a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("angle: %.2f\n", a)
	r, err := chip.AngleRaw() // Read scaled angle count, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("angle_raw: %d\n", r)
	ra, err := chip.RawAngle() // Read raw unscaled angle, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("raw_angle: %d\n", ra)
	g, err := chip.AGC() // Read AGC value, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("agc: %d\n", g)
	mag, err := chip.Magnitude() // Read CORDIC magnitude, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("magnitude: %d\n", mag)
	bc, err := chip.BurnCount() // Read burn count, () → (uint8 0-3, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("burn_count: %d\n", bc)
}
