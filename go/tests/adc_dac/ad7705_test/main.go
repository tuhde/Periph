//go:build linux && !tinygo

// AD7705 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_BUS:", err)
		os.Exit(2)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_DEVICE:", err)
		os.Exit(2)
	}

	conn, err := connection.NewSPIConnection(bus, device, 3, 1_000_000, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	chip, err := adcdac.NewAD7705Minimal(conn, 2.5, adcdac.MCLK2_4576MHz, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new minimal:", err)
		os.Exit(2)
	}

	raw, err := chip.ReadRaw()
	check("readRaw returns int", err == nil)
	check("readRaw in [0, 65535]", err == nil && raw <= 65535)

	v, err := chip.ReadVoltage()
	check("readVoltage returns float", err == nil)
	check("readVoltage in [-2.5, 2.5]", err == nil && v >= -2.5 && v <= 2.5)

	chip2, err := adcdac.NewAD7705Full(conn, 2.5, adcdac.MCLK2_4576MHz, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new full:", err)
		os.Exit(2)
	}

	raw1, err := chip2.ReadRawChannel(1)
	check("readRawChannel(1) in [0, 65535]", err == nil && raw1 <= 65535)
	v1, err := chip2.ReadVoltageChannel(1)
	check("readVoltageChannel(1) returns float", err == nil)
	check("readVoltageChannel(1) in [-2.5, 2.5]", err == nil && v1 >= -2.5 && v1 <= 2.5)

	raw2, err := chip2.ReadRawChannel(2)
	check("readRawChannel(2) in [0, 65535]", err == nil && raw2 <= 65535)
	v2, err := chip2.ReadVoltageChannel(2)
	check("readVoltageChannel(2) returns float", err == nil)
	check("readVoltageChannel(2) in [-2.5, 2.5]", err == nil && v2 >= -2.5 && v2 <= 2.5)

	err = chip2.Configure(1, adcdac.GAIN2, true, false, 60)
	check("configure(1, GAIN2) accepted", err == nil)
	err = chip2.Configure(2, adcdac.GAIN4, false, true, 60)
	check("configure(2, GAIN4) accepted", err == nil)
	err = chip2.Configure(1, adcdac.GAIN128, true, true, 50)
	check("configure(1, GAIN128) accepted", err == nil)

	err = chip2.SelfCalibrate(1)
	check("selfCalibrate(1) accepted", err == nil)
	err = chip2.SelfCalibrate(2)
	check("selfCalibrate(2) accepted", err == nil)

	err = chip2.SystemCalibrateZero(1)
	check("systemCalibrateZero(1) accepted", err == nil)
	err = chip2.SystemCalibrateFull(1)
	check("systemCalibrateFull(1) accepted", err == nil)

	off1, err := chip2.GetOffsetCalibration(1)
	check("getOffsetCalibration(1) in [0, 2^24-1]", err == nil && off1 <= 0xFFFFFF)
	err = chip2.SetOffsetCalibration(off1, 1)
	check("setOffsetCalibration(1) accepted", err == nil)

	gain1, err := chip2.GetGainCalibration(1)
	check("getGainCalibration(1) in [0, 2^24-1]", err == nil && gain1 <= 0xFFFFFF)
	err = chip2.SetGainCalibration(gain1, 1)
	check("setGainCalibration(1) accepted", err == nil)

	off2, err := chip2.GetOffsetCalibration(2)
	check("getOffsetCalibration(2) in [0, 2^24-1]", err == nil && off2 <= 0xFFFFFF)
	gain2, err := chip2.GetGainCalibration(2)
	check("getGainCalibration(2) in [0, 2^24-1]", err == nil && gain2 <= 0xFFFFFF)

	err = chip2.Standby()
	check("standby accepted", err == nil)
	err = chip2.Wakeup()
	check("wakeup accepted", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed == 0 {
		os.Exit(0)
	}
	os.Exit(1)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
