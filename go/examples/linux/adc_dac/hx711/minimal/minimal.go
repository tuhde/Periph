//go:build linux && !tinygo

// HX711 minimal example — Linux host.
//
// Constructs the driver with a HX711Transport on /dev/gpiochip0
// (DOUT on line 2, PD_SCK on line 3), then loops reading signed
// 24-bit ADC values from Channel A at Gain 128 every 500 ms.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	dout, err := strconv.Atoi(envOr("HX711_DOUT", "2"))
	if err != nil {
		panic(err)
	}
	pdSck, err := strconv.Atoi(envOr("HX711_PD_SCK", "3"))
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewHX711Transport(dout, pdSck) // Create HX711 transport, (dout=2, pd_sck=3) → (*HX711Transport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	hx, err := adcdac.NewHX711Minimal(tr) // Create HX711 driver, (transport) → (*HX711Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read_raw:", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		fmt.Printf("raw=%d\n", raw)
		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
