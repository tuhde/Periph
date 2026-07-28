//go:build linux && !tinygo

// DHT11 demo example — Linux host.
//
// Indoor comfort monitor: reads temperature and humidity every
// 5 seconds, printing a one-line status with both values plus a
// comfort assessment ("dry" if RH < 30%, "comfortable" if 30–60%,
// "humid" if > 60%). Uses ReadRetry to handle occasional checksum
// errors gracefully; when a read still fails after all retries,
// prints a warning and continues.
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

	dht, err := humidity.NewDHT11Full(conn) // Create DHT11 driver, (connection) → (*DHT11Full, error)
	if err != nil {
		panic(err)
	}

	// --- Indoor comfort monitor loop ---
	// Read every 5 s, classify comfort from RH, surface the
	// current status. Retry up to 3 times per attempt so a
	// single dropped frame doesn't break the run.
	for n := 0; n < 60; n++ {
		t, h, err := dht.ReadRetry(3) // Read with retry, (max_retries=3) → (float32 °C, float32 %RH, error)
		if err != nil {
			fmt.Fprintf(os.Stderr, "warning: read failed at sample %d: %v\n", n, err)
			time.Sleep(5 * time.Second)
			continue
		}
		comfort := "comfortable"
		switch {
		case h < 30.0:
			comfort = "dry"
		case h > 60.0:
			comfort = "humid"
		}
		fmt.Printf("[%02d] T=%.1f C  H=%.1f %%RH  %s\n", n, t, h, comfort)
		time.Sleep(5 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
