//go:build linux && !tinygo

// BMA150 complete example — exercise every method in the public API.
package main

import (
	"fmt"
	"os"
	"strconv"

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
	rx, ry, rz, _ := chip.ReadRaw()
	temp, _ := chip.ReadTemperature()
	ready, _ := chip.NewDataAvailable()
	_ = chip.SetShadow(false)
	_ = chip.SetLowG(0.4, 40, 0, 0)
	_ = chip.SetHighG(4.0, 2, 0, 0)
	_ = chip.SetAnyMotion(0.5, 3)
	_ = chip.SetAlert(false)
	_ = chip.SetLatch(true)
	status, _ := chip.PollInterrupt()
	_ = chip.ClearInterrupt()
	_ = chip.SetWakeUp(true, 80)
	x, y, z, _ := chip.Read()
	al, ml, _ := chip.ReadVersion()
	c1, _ := chip.ReadCustomer(0)
	_ = chip.WriteCustomer(0, 0xA5)
	st, _ := chip.SelfTest()
	_ = chip.SoftReset()
	_ = chip.Sleep()
	_ = chip.Wake()
	fmt.Printf("raw=(%d,%d,%d) temp=%.1f status=0x%02X al=%d ml=%d c1=0x%02X st=%s\n",
		rx, ry, rz, temp, status, al, ml, c1, map[bool]string{true: "PASS", false: "FAIL"}[st])
	fmt.Printf("xyz=(%.3f, %.3f, %.3f) ready=%v\n", x, y, z, ready)
}
