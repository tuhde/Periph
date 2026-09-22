//go:build linux && !tinygo

// DS3231 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, 0x68, nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	clock, err := rtc.NewDS3231Minimal(conn) // Create DS3231 driver, (conn) → (*DS3231Minimal, error)
	if err != nil {
		panic(err)
	}

	now := time.Now().UTC()
	weekday := int(now.Weekday())
	if weekday == 0 {
		weekday = 7 // Go's Sunday=0 -> ISO 8601 Sunday=7
	}
	if err := clock.SetDatetime(now.Year(), int(now.Month()), now.Day(), weekday, now.Hour(), now.Minute(), now.Second()); err != nil { // Set clock/calendar, (year, month, day, weekday, hour, minute, second) → error
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
		fmt.Printf("%04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C\n",
			year, month, day, weekday, hour, minute, second, temp)
		time.Sleep(1 * time.Second)
	}
}
