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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	gyro, err := gyroscope.NewL3GD20HFull(conn, false)
	if err != nil {
		panic(err)
	}

	gyro.Configure(gyroscope.L3GD20HODR190Hz, 0, gyroscope.L3GD20HFS500DPS) // Configure, (odr, bw, fullScale)

	gyro.ConfigureHighpass(gyroscope.L3GD20HHPMNormal, 0) // Configure HPF
	gyro.EnableHighpass(true)                              // Enable HPF

	gyro.ConfigureFIFO(gyroscope.L3GD20HFIFOFIFO, 10) // Configure FIFO
	gyro.EnableFIFO(true)                              // Enable FIFO

	gyro.SetPowerMode(gyroscope.L3GD20HPowerNormal)    // Set power mode

	temp, _ := gyro.Temperature()
	fmt.Printf("Temperature: %d\n", temp)

	for {
		drdy, _ := gyro.DataReady()
		if drdy {
			x, y, z, _ := gyro.AngularRate()
			fmt.Printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z)

			rx, ry, rz, _ := gyro.AngularRateRaw()
			fmt.Printf("  raw: x=%d y=%d z=%d\n", rx, ry, rz)

			level, _ := gyro.FIFOLevel()
			if level > 0 {
				samples, _ := gyro.ReadFIFO()
				fmt.Printf("FIFO: %d samples\n", len(samples))
			}
		}
	}
}