//go:build linux && !tinygo

// TSL2561 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	fmt.Fprintln(os.Stderr, what+":", err)
	os.Exit(2)
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	sensor, err := light.NewTSL2561Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	lx, err := sensor.ReadLux()
	check("read_lux_in_range", err == nil && lx >= 0 && lx <= 40000)

	full, err := light.NewTSL2561Full(conn)
	if err != nil {
		fail("init_full", err)
		return
	}
	id, err := full.ReadID()
	check("read_id_partno", err == nil && id>>4 == 1)
	on, err := full.IsPowered()
	check("is_powered", err == nil && on)
	_, _, err = full.ReadRaw()
	check("read_raw", err == nil)
	full.SetGain(16)
	full.SetIntegrationTime(101)
	check("gain_integration_roundtrip", full.Gain() == 16 && full.IntegrationTime() == 101)
	full.SetGain(1)
	full.SetIntegrationTime(402)
	lx, err = full.AutoRange()
	check("auto_range_lux", err == nil && lx >= 0)
	full.SetThresholds(100, 30000)
	lo, hi, err := full.Thresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 30000)
	check("configure_interrupt", full.ConfigureInterrupt(0, 0) == nil)
	check("clear_interrupt", full.ClearInterrupt() == nil)
	_, err = full.PollInterrupt()
	check("poll_interrupt", err == nil)
	full.PowerDown()
	on, _ = full.IsPowered()
	check("power_down", !on)
	full.PowerUp()
	on, _ = full.IsPowered()
	check("power_up", on)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
