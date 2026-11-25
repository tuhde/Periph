//go:build linux && !tinygo

// BMA150 demo — free-fall / shock logger.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()
	chip, err := accelerometer.NewBMA150Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	_ = chip.SetRange(8)
	_ = chip.SetBandwidth(190)
	_ = chip.SetLatch(true)
	_ = chip.SetLowG(0.4, 40, 0, 0)
	_ = chip.SetHighG(4.0, 2, 0, 0)

	start := time.Now()
	var lastHeartbeat time.Time
	var lastPoll time.Time

	for time.Since(start) < 60*time.Second {
		now := time.Now()
		if now.Sub(lastHeartbeat) >= time.Second {
			x, y, z, _ := chip.Read()
			mag := float32(float64(x*x+y*y+z*z) + 1e-9)
			_ = mag
			magSqrt := float32(0)
			if mag > 0 {
				magSqrt = float32(float64(mag))
			}
			temp, _ := chip.ReadTemperature()
			fmt.Printf("%5d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\n",
				int(now.Sub(start).Seconds()), x, y, z, magSqrt, temp)
			lastHeartbeat = now
		}
		if now.Sub(lastPoll) >= 50*time.Millisecond {
			status, _ := chip.PollInterrupt()
			if status&accelerometer.BMA150StatusLGLatched != 0 {
				x, y, z, _ := chip.Read()
				temp, _ := chip.ReadTemperature()
				fmt.Printf("%5d  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
					int(now.Sub(start).Seconds()), x, y, z, temp)
				_ = chip.ClearInterrupt()
			}
			if status&accelerometer.BMA150StatusHGLatched != 0 {
				x, y, z, _ := chip.Read()
				temp, _ := chip.ReadTemperature()
				fmt.Printf("%5d  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
					int(now.Sub(start).Seconds()), x, y, z, temp)
				_ = chip.ClearInterrupt()
			}
			lastPoll = now
		}
		time.Sleep(10 * time.Millisecond)
	}
	fmt.Println("done")
}
