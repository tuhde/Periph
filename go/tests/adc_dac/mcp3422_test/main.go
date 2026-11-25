//go:build linux && !tinygo

// MCP3422 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
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

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Minimal(conn)
	if err != nil {
		fail("init", err)
		os.Exit(2)
	}
	check("construct_minimal", adc != nil)
	raw, err := adc.ReadRaw(0)
	check("raw_in_16_bit_range", err == nil && raw >= -32768 && raw <= 32767)
	v, err := adc.ReadVoltage(1)
	check("voltage_in_range", err == nil && v >= -2.048 && v <= 2.048)
	both, err := adc.ReadAll()
	check("read_all_two_values", err == nil && len(both) == 2)

	full, err := adcdac.NewMCP3422Full(conn)
	if err != nil {
		fail("init full", err)
		os.Exit(2)
	}
	check("selection_accepted", full.SetResolution(12) == nil && full.SetGain(2) == nil)
	check("selection_roundtrip", full.GetResolution() == 12 && full.GetGain() == 2)
	check("full_scale", near(full.FullScale(), 1.024, 1e-6))
	r12, err := full.ReadRaw(0)
	check("one_shot_12_bit_range", err == nil && r12 >= -2048 && r12 <= 2047)
	full.SetGain(1)
	check("start_continuous", full.StartContinuous(0) == nil && full.IsContinuous())
	rc, err := full.ReadContinuousRaw()
	check("continuous_sample_in_range", err == nil && rc >= -2048 && rc <= 2047)
	cfg, err := full.ReadConfig()
	check("read_config_continuous_bit", err == nil && cfg&0x10 != 0)
	check("stopped", full.StopContinuous() == nil && !full.IsContinuous())
	scan, err := full.ReadScan([]int{0, 1})
	check("read_scan_two_values", err == nil && len(scan) == 2)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
