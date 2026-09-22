//go:build linux && !tinygo

// TPIC6B595 minimal example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	mode := envOr("SIPO_MODE", "hw") // 'hw' (spidev) or 'sw' (bit-bang)
	bus, err := strconv.Atoi(envOr("SIPO_SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SIPO_SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	rc, err := strconv.Atoi(envOr("SIPO_RCK", "5"))
	if err != nil {
		panic(err)
	}
	sr, err := strconv.Atoi(envOr("SIPO_SRCLR", "6"))
	if err != nil {
		panic(err)
	}
	g, err := strconv.Atoi(envOr("SIPO_G", "13"))
	if err != nil {
		panic(err)
	}
	serIn, err := strconv.Atoi(envOr("SIPO_SER_IN", "19"))
	if err != nil {
		panic(err)
	}
	srck, err := strconv.Atoi(envOr("SIPO_SRCK", "26"))
	if err != nil {
		panic(err)
	}

	var conn *connection.SIPOConnection
	if mode == "sw" {
		conn, err = connection.NewSIPOSoftwareSPI(serIn, srck, rc, sr, g, nil) // Create SiPo connection, (serIn=19, srck=26, rck=5, srclr=6, g=13, enPin=nil) → (*SIPOConnection, error)
	} else {
		conn, err = connection.NewSIPOHardwareSPI(bus, device, 1_000_000, rc, sr, g, nil) // Create SiPo connection, (bus=0, device=0, maxSpeedHz=1000000, rck=5, srclr=6, g=13, enPin=nil) → (*SIPOConnection, error)
	}
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := ioexpander.NewTPIC6B595Minimal(conn, 1) // Create TPIC6B595 driver, (connection, numDevices=1) → (*TPIC6B595Minimal, error)
	if err != nil {
		panic(err)
	}

	p0 := chip.Pin(0) // Get pin proxy, (n=0) → TPIC6B595Pin
	p7 := chip.Pin(7) // Get pin proxy, (n=7) → TPIC6B595Pin

	for {
		if err := p0.Set(true); err != nil { // Set DMOS output ON, (high=true) → error
			panic(err)
		}
		if err := p7.Set(false); err != nil { // Set DMOS output OFF, (high=false) → error
			panic(err)
		}
		fmt.Println("p0=ON  p7=OFF")
		time.Sleep(500 * time.Millisecond)
		if err := p0.Set(false); err != nil { // Set DMOS output OFF, (high=false) → error
			panic(err)
		}
		if err := p7.Set(true); err != nil { // Set DMOS output ON, (high=true) → error
			panic(err)
		}
		fmt.Println("p0=OFF p7=ON")
		time.Sleep(500 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
