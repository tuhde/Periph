//go:build tinygo

// DS3231 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x68, nil, nil) // Create I2C connection, (i2c, addr=0x68) → *I2CConnection
	defer conn.Close()

	clock, err := rtc.NewDS3231Minimal(conn) // Create DS3231 driver, (conn) → (*DS3231Minimal, error)
	if err != nil {
		panic(err)
	}

	if err := clock.SetDatetime(2026, 9, 22, 2, 12, 0, 0); err != nil { // Set clock/calendar, (year, month, day, weekday, hour, minute, second) → error
		panic(err)
	}

	for i := 0; i < 10; i++ {
		year, month, day, weekday, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (int, int, int, int, int, int, int, error)
		if err != nil {
			panic(err)
		}
		temp, err := clock.ReadTemperature() // Read temperature, () → (float32, error) °C
		if err != nil {
			panic(err)
		}
		println(year, "-", month, "-", day, " wd=", weekday, " ", hour, ":", minute, ":", second, "  ", temp, "C")
		time.Sleep(1 * time.Second)
	}
}
