//go:build linux && !tinygo

// CS43L22 minimal example — Linux host.
//
// Bring up the headphone output and set the volume.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.CS43L22I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x4A) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	dac, err := adcdac.NewCS43L22Minimal(conn, nil) // Create CS43L22 driver, (conn, resPin=nil) → (*CS43L22Minimal, error)
	must(err)
	must(dac.Init())         // Reset, verify ID, load defaults, () → error
	must(dac.Start())        // Power up the DAC once MCLK is running, () → error
	must(dac.SetVolume(-20)) // Set master volume, (db -102–12 dB) → error
	must(dac.Mute(false))    // Unmute the headphone outputs, (on) → error
	for i := 0; i < 5; i++ {
		v, err := dac.GetVolume() // Read back master volume, () → (float32 dB, error)
		must(err)
		fmt.Println(v)
		time.Sleep(time.Second)
	}
	must(dac.Stop()) // Power down the DAC, () → error
}
