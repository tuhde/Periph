//go:build linux && !tinygo

// HX710A demo example — Linux host.
//
// Temperature-monitored load cell: tares the scale at startup
// (averaging 10 readings), then loops continuously reading the
// weight every 500 ms and printing a "-> N g" update whenever the
// reading changes by more than 1 gram. Every 10th iteration (~5 s),
// also reads the temperature channel and prints the raw code
// alongside the weight — an uncalibrated ADC code intended for
// relative drift tracking, not an absolute °C reading. The
// scaleFactor constant is calibrated for a 100 g reference weight;
// substitute your own.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const scaleFactor = 420.0

func main() {
	dout, err := strconv.Atoi(envOr("HX710A_DOUT", "2"))
	if err != nil {
		panic(err)
	}
	pdSck, err := strconv.Atoi(envOr("HX710A_PD_SCK", "3"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewHX711Connection(dout, pdSck, nil) // Create HX711 transport connection, (dout=2, pd_sck=3) → (*HX711Connection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	hx, err := adcdac.NewHX710AFull(conn) // Create HX710A driver, (connection) → (*HX710AFull, error)
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

	// --- Continuous read loop with change-only printing and periodic temperature ---
	// Samples every 500 ms but only emits a weight line when the
	// value shifts by more than 1 g. Every 10th iteration (~5 s),
	// also samples the on-chip temperature sensor for relative
	// drift compensation — the datasheet's stated purpose, not an
	// absolute °C measurement.
	var lastWeight float32
	first := true
	iteration := 0
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

		if iteration%10 == 0 {
			tempRaw, err := hx.ReadTemperatureRaw() // Read raw on-chip temperature code, () → (int32, error)
			if err == nil {
				fmt.Printf("temp raw=%d\n", tempRaw)
			}
		}
		iteration++

		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
