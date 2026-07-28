//go:build linux && !tinygo

// DHT11 minimal example — Linux host.
//
// Constructs the driver with a DHTxxConnection on a /dev/gpiochip0
// line, then loops reading temperature and humidity once per
// 2 seconds (DHT11's minimum sampling interval).
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	line, err := strconv.Atoi(envOr("DHT11_LINE", "4"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewDHTxxConnection(line, -1, nil) // Create DHTxx connection, (line=4, out_line=-1) → (*DHTxxConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	dht, err := humidity.NewDHT11Minimal(conn) // Create DHT11 driver, (connection) → (*DHT11Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, h, err := dht.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read:", err)
			time.Sleep(2 * time.Second)
			continue
		}
		fmt.Printf("T=%.1f C  H=%.1f %%RH\n", t, h)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
