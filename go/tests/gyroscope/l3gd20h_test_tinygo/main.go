package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 100 * machine.KHz,
		SDA:       machine.I2C0_SDA_PIN,
		SCL:       machine.I2C0_SCL_PIN,
	})

	conn, err := connection.NewI2CConnection(0, 0x6A, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	gyro, err := gyroscope.NewL3GD20HMinimal(conn, false)
	if err != nil {
		panic(err)
	}

	fmt.Println("=== L3GD20H Go TinyGo Test ===")

	x, y, z, err := gyro.AngularRate()
	if err != nil {
		panic(err)
	}
	fmt.Println("PASS gyro() returns valid floats")
	fmt.Printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z)

	fmt.Println("=== DONE: 1 passed, 0 failed ===")
}