//go:build tinygo

// AD7705 hardware test — TinyGo (Pico W).
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adcdac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.SPI0.Configure(machine.SPIConfig{
		Frequency: 1_000_000,
		Mode:      3,
		SCK:       machine.GP18,
		MOSI:      machine.GP19,
		MISO:      machine.GP16,
	})
	cs := machine.GP17
	conn := connection.NewSPIConnection(machine.SPI0, cs, nil, nil)

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

	chip, err := adcdac.NewAD7705Minimal(conn, 2.5, adcdac.MCLK2_4576MHz)
	if err != nil {
		fmt.Fprintln(fmt, "new minimal:", err)
		return
	}

	raw, err := chip.ReadRaw()
	check("readRaw returns int", err == nil)
	check("readRaw in [0, 65535]", err == nil && raw <= 65535)

	v, err := chip.ReadVoltage()
	check("readVoltage returns float", err == nil)
	check("readVoltage in [-2.5, 2.5]", err == nil && v >= -2.5 && v <= 2.5)

	chip2, err := adcdac.NewAD7705Full(conn, 2.5, adcdac.MCLK2_4576MHz)
	if err != nil {
		fmt.Fprintln(fmt, "new full:", err)
		return
	}

	raw1, err := chip2.ReadRawChannel(1)
	check("readRawChannel(1) in [0, 65535]", err == nil && raw1 <= 65535)
	v1, err := chip2.ReadVoltageChannel(1)
	check("readVoltageChannel(1) returns float", err == nil)

	raw2, err := chip2.ReadRawChannel(2)
	check("readRawChannel(2) in [0, 65535]", err == nil && raw2 <= 65535)
	v2, err := chip2.ReadVoltageChannel(2)
	check("readVoltageChannel(2) returns float", err == nil)

	err = chip2.Configure(1, adcdac.GAIN2, true, false, 60)
	check("configure(1, GAIN2) accepted", err == nil)
	err = chip2.Configure(2, adcdac.GAIN4, false, true, 60)
	check("configure(2, GAIN4) accepted", err == nil)

	err = chip2.SelfCalibrate(1)
	check("selfCalibrate(1) accepted", err == nil)
	err = chip2.SelfCalibrate(2)
	check("selfCalibrate(2) accepted", err == nil)

	off1, err := chip2.GetOffsetCalibration(1)
	check("getOffsetCalibration(1) in [0, 2^24-1]", err == nil && off1 <= 0xFFFFFF)
	err = chip2.SetOffsetCalibration(off1, 1)
	check("setOffsetCalibration(1) accepted", err == nil)

	gain1, err := chip2.GetGainCalibration(1)
	check("getGainCalibration(1) in [0, 2^24-1]", err == nil && gain1 <= 0xFFFFFF)
	err = chip2.SetGainCalibration(gain1, 1)
	check("setGainCalibration(1) accepted", err == nil)

	err = chip2.Standby()
	check("standby accepted", err == nil)
	err = chip2.Wakeup()
	check("wakeup accepted", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
