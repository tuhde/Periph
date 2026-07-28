//go:build linux && !tinygo

// AS5600 demo example — Linux host.
//
// Motor feedback monitor: reads the angle 10 times per second, prints
// degrees, raw count, and AGC value. When the AGC drifts outside 64–192
// (in 5 V mode) it prints a magnet distance warning. When STATUS changes
// (magnet removed or reinserted) it prints a notification.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x36) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	// --- Motor feedback monitor: read angle 10 times per second ---
	// AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
	// In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.
	chip, err := magnetometer.NewAs5600Full(conn) // Create AS5600 driver, (connection) → (*As5600Full, error)
	if err != nil {
		panic(err)
	}

	prevStatus, _ := chip.StatusByte() // Read raw status, () → (uint8, error)

	for n := 0; n < 100; n++ {
		a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
		if err != nil {
			panic(err)
		}
		r, err := chip.RawAngle() // Read raw unscaled angle, () → (uint16 0-4095, error)
		if err != nil {
			panic(err)
		}
		g, err := chip.AGC() // Read AGC value, () → (uint8, error)
		if err != nil {
			panic(err)
		}

		// --- Check for status changes (magnet inserted/removed) ---
		status, _ := chip.StatusByte()
		if status != prevStatus {
			if md, _ := chip.IsMagnetDetected(); !md {
				fmt.Println("[MAGNET REMOVED] MD=0")
			} else {
				mh, _ := chip.IsMagnetTooStrong()
				ml, _ := chip.IsMagnetTooWeak()
				fmt.Printf("[MAGNET DETECTED] MD=1  MH=%v  ML=%v\n", mh, ml)
			}
			prevStatus = status
		}

		// --- AGC health check ---
		if md, _ := chip.IsMagnetDetected(); md {
			tag := "[OK]"
			if g < 64 || g > 192 {
				tag = "[AGC low — magnet weak or too far]"
			}
			fmt.Printf("angle=%.2f deg  raw=%d  agc=%d  %s\n", a, r, g, tag) // Read absolute angle, () → (float64 degrees, error)
		}

		time.Sleep(100 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
