//go:build linux && !tinygo

// HX711 demo example — Linux host.
//
// Kitchen scale: tares the scale at startup (averaging 10 readings),
// then loops continuously reading the weight every 500 ms and
// printing the result in grams. When the reading changes by more
// than 1 gram from the previous value, prints a "-> N g" update
// line. The SCALE_FACTOR constant is calibrated for a 100 g
// reference weight; substitute your own.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

const scaleFactor = 420.0

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

	hx, err := adcdac.NewHX711Full(tr) // Create HX711 driver, (transport) → (*HX711Full, error)
	if err != nil {
		panic(err)
	}

	// --- Tare the scale at startup ---
	// Averages 10 readings, stores as the zero offset. After this,
	// ReadWeight returns (raw - offset) / scale_factor.
	fmt.Println("Taring...")
	if err := hx.Tare(10); err != nil { // Capture zero offset, (times=10) → error
		fmt.Fprintln(os.Stderr, "tare:", err)
		return
	}
	hx.SetScale(scaleFactor) // Set scale factor, (factor=scaleFactor) → ()
	fmt.Println("Ready. Place an item on the scale.")

	// --- Continuous read loop with change-only printing ---
	// Samples every 500 ms but only emits a line when the value
	// shifts by more than 1 g, keeping the log readable.
	var lastWeight float32
	first := true
	for {
		w, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read_weight:", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		if first || (w-lastWeight > 1.0) || (lastWeight-w > 1.0) {
			fmt.Printf("-> %.1f g\n", w)
			lastWeight = w
			first = false
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
