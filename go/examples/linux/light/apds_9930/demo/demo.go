//go:build linux && !tinygo

// APDS-9930 demo example — Linux host. Adaptive backlight + screen-lock.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

const (
	dimLuxThreshold = 10.0
	proxScreenOff  = 400
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := light.NewAPDS9930Full(conn) // Create APDS-9930 Full driver, (connection) → (*APDS9930Full, error)
	if err != nil {                          // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive
		panic(err)
	}

	// --- Sample lux and proximity once per second for 30 cycles ---
	// The user is encouraged to cover the sensor with a hand (proximity
	// rises) and to dim/undim the room light to watch both action lines
	// fire.
	for i := 0; i < 30; i++ {
		time.Sleep(time.Second)
		lx, _ := chip.Lux() // Read ambient illuminance, () → (float64 lx, error)
		p, _ := chip.Proximity()
		fmt.Printf("lux=%.1f lx  proximity=%d\n", lx, p)
		if lx < dimLuxThreshold {
			fmt.Println("  -> dim backlight")
		}
		if p > proxScreenOff {
			fmt.Println("  -> disable screen")
		}
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}