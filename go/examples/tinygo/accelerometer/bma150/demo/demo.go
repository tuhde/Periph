//go:build tinygo

// BMA150 demo — free-fall / shock logger, TinyGo / Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x38, nil, nil)
	if err != nil {
		fmt.Println("connection:", err)
		return
	}
	chip, err := accelerometer.NewBMA150Full(conn)
	if err != nil {
		fmt.Println("init:", err)
		return
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
			temp, _ := chip.ReadTemperature()
			fmt.Printf("%5d  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
				int(now.Sub(start).Seconds()), x, y, z, temp)
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
