//go:build tinygo

// AXP2101 hardware test — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x34, nil, nil)
	if err != nil {
		fmt.Println("connection:", err)
		return
	}
	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	chip, err := power.NewAXP2101Minimal(conn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	check("construct_minimal", true)
	v, err := chip.BatteryVoltage()
	check("battery_voltage_plausible", err == nil && v >= 0 && v <= 5)
	pct, err := chip.BatteryPercent()
	check("battery_percent_range", err == nil && pct >= 0 && pct <= 100)
	state, err := chip.ChargeState()
	check("charge_state_range", err == nil && state >= 0 && state <= 5)
	_, err = chip.VbusPresent()
	check("vbus_present_no_error", err == nil)

	full, err := power.NewAXP2101Full(conn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	check("construct_full", true)
	vb, err := full.VbusVoltage()
	check("vbus_voltage_range", err == nil && vb >= 0 && vb <= 15)
	vs, err := full.SystemVoltage()
	check("system_voltage_range", err == nil && vs >= 0 && vs <= 6)
	ts, err := full.TSVoltage()
	check("ts_voltage_range", err == nil && ts >= 0 && ts <= 4)
	td, err := full.DieTemperature()
	check("die_temperature_range", err == nil && td >= -40 && td <= 150)
	all, err := full.ReadAll()
	check("read_all", err == nil && all.BatteryPercent >= 0)
	st, err := full.Status()
	check("status_direction", err == nil && (st.BatteryDirection == "standby" || st.BatteryDirection == "charge" ||
		st.BatteryDirection == "discharge" || st.BatteryDirection == "reserved"))
	en, volts, err := full.GetRail("dcdc1")
	check("get_rail_dcdc1", err == nil && (en || !en) && volts >= 1.4 && volts <= 3.5)

	check("set_input_voltage_limit", full.SetInputVoltageLimit(4.36) == nil)
	check("write_data_buffer", full.WriteDataBuffer(0, 0x5A) == nil)
	b, err := full.ReadDataBuffer(0)
	check("data_buffer_roundtrip", err == nil && b == 0x5A)
	check("enable_interrupt", full.EnableInterrupt(power.AXP2101SourceVbusInsert) == nil)
	_, err = full.PollInterrupt()
	check("poll_interrupt", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	for {
		time.Sleep(time.Second)
	}
}
