//go:build linux && !tinygo

// RFM95W minimal example — Linux host.
//
// Constructs the driver on /dev/spidevB.D at 868 MHz, then sends a
// "hello" packet every 2 seconds.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	freq, err := strconv.ParseUint(envOr("RFM9X_FREQUENCY_HZ", "868000000"), 10, 32)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 5_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=5 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	radio, err := comms.NewRFM95Minimal(conn, uint32(freq)) // Create RFM95W driver, (connection, frequency_hz=868e6) → (*RFM95Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := radio.Send([]byte("hello")); err != nil { // Send packet, (data=bytes ≤255 B) → error
			panic(err)
		}
		fmt.Println("sent; sleeping 2 s")
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
