//go:build tinygo

// DHT11 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Wires the DHT11 DATA line to GP15 (4.7 kΩ pull-up to 3V3 required).
// Reads temperature and humidity in a loop, printing once per
// 2 seconds (the DHT11's minimum sampling interval).
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
	dht, err := humidity.NewDHT11Minimal(tr)         // Create DHT11 driver, (transport) → (*DHT11Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		t, h, err := dht.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		if err != nil {
			fmt.Printf("read: %v\n", err)
			time.Sleep(2 * time.Second)
			continue
		}
		fmt.Printf("T=%.1f C  H=%.1f %%RH\n", t, h)
		time.Sleep(2 * time.Second)
	}
}
