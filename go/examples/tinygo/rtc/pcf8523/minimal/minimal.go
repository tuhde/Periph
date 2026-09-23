//go:build tinygo

// PCF8523 minimal example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, rtc.PCF8523Address, nil, nil) // Create I2C connection, (i2c, addr=0x68) → *I2CConnection
	defer conn.Close()

	clock, err := rtc.NewPCF8523Minimal(conn) // Create PCF8523 driver, (conn) → (*PCF8523Minimal, error)
	must(err)

	for i := 0; i < 10; i++ {
		year, month, day, _, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
		must(err)
		fmt.Printf("%04d-%02d-%02d %02d:%02d:%02d\n", year, month, day, hour, minute, second)
		time.Sleep(1 * time.Second)
	}
}
