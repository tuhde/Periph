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

	gyro, err := gyroscope.NewL3GD20HFull(conn, false)
	if err != nil {
		panic(err)
	}

	gyro.Configure(gyroscope.L3GD20HODR190Hz, 0, gyroscope.L3GD20HFS500DPS)
	gyro.ConfigureHighpass(gyroscope.L3GD20HHPMNormal, 0)
	gyro.EnableHighpass(true)
	gyro.ConfigureFIFO(gyroscope.L3GD20HFIFOFIFO, 10)
	gyro.EnableFIFO(true)
	gyro.SetPowerMode(gyroscope.L3GD20HPowerNormal)

	who, _ := gyro.WHOAMI()
	fmt.Printf("WHO_AM_I: 0x%02X\n", who)

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