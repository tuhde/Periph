//go:build tinygo

// DHT11 demo example — TinyGo / Raspberry Pi Pico W.
//
// Indoor comfort monitor on a Pico W with the DHT11 DATA line on
// GP15. Reads temperature and humidity every 5 seconds, prints a
// one-line status with both values plus a comfort assessment
// ("dry" / "comfortable" / "humid"). Uses ReadRetry to handle
// occasional checksum errors gracefully.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	tr := transport.NewDHTxxTransport(machine.GP15) // Create DHTxx transport, (pin=GP15) → (*DHTxxTransport)
	dht, err := humidity.NewDHT11Full(tr)           // Create DHT11 driver, (transport) → (*DHT11Full, error)
	if err != nil {
		panic(err)
	}

	// --- Indoor comfort monitor loop ---
	// Read every 5 s, classify comfort from RH, surface the
	// current status. Retry up to 3 times per attempt.
	for n := 0; n < 60; n++ {
		t, h, err := dht.ReadRetry(3) // Read with retry, (max_retries=3) → (float32 °C, float32 %RH, error)
		if err != nil {
			fmt.Printf("warning: read failed at sample %d: %v\n", n, err)
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
