package main

import (
	"fmt"
	"os"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus := 1
	if b := os.Getenv("I2C_BUS"); b != "" {
		fmt.Sscanf(b, "%d", &bus)
	}
	addr := 0x6A
	if a := os.Getenv("I2C_ADDR"); a != "" {
		fmt.Sscanf(a, "%x", &addr)
	}

	conn, err := connection.NewI2CConnection(bus, addr, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	gyro, err := gyroscope.NewL3GD20HMinimal(conn, false)
	if err != nil {
		panic(err)
	}

	for {
		x, y, z, err := gyro.AngularRate()
		if err != nil {
			panic(err)
		}
		fmt.Printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z)
	}
}