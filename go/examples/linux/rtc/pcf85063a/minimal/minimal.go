//go:build linux && !tinygo

// PCF85063A minimal example — Linux host.
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

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, rtc.PCF85063AAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x51) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	clock, err := rtc.NewPCF85063AMinimal(conn) // Create PCF85063A driver, (conn) → (*PCF85063AMinimal, error)
	must(err)

	for i := 0; i < 10; i++ {
		year, month, day, _, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
		must(err)
		fmt.Printf("%04d-%02d-%02d %02d:%02d:%02d\n", year, month, day, hour, minute, second)
		time.Sleep(1 * time.Second)
	}
}
