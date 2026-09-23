//go:build linux && !tinygo

// PCF8523 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
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

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(2)
	}
}

func done() {
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, rtc.PCF8523Address, nil, nil)
	must(err)
	defer conn.Close()
	clock, err := rtc.NewPCF8523Minimal(conn)
	check("construct_minimal", err == nil)
	if err != nil {
		done()
		return
	}
	must(clock.SetDatetime(2026, 9, 23, 3, 14, 30, 0))
	year, month, day, weekday, hour, minute, _, err := clock.GetDatetime()
	check("get_datetime_roundtrip", err == nil && year == 2026 && month == 9 && day == 23 && weekday == 3 &&
		hour == 14 && (minute == 30 || minute == 31))

	full, err := rtc.NewPCF8523Full(conn)
	check("construct_full", err == nil)
	if err != nil {
		done()
		return
	}
	stopped, err := full.OscillatorStopped()
	check("oscillator_running_after_set_datetime", err == nil && !stopped)

	alarm := rtc.PCF8523Alarm{Minute: 15, Hour: 6, Day: rtc.PCF8523AlarmDisabled, Weekday: rtc.PCF8523AlarmDisabled}
	must(full.SetAlarm(alarm))
	got, err := full.GetAlarm()
	check("alarm_roundtrip", err == nil && got == alarm)
	must(full.SetAlarm(rtc.PCF8523Alarm{Minute: -1, Hour: -1, Day: -1, Weekday: -1}))

	must(full.SetOffset(-3, rtc.PCF8523OffsetEveryMinute))
	offset, mode, err := full.GetOffset()
	check("offset_roundtrip", err == nil && offset == -3 && mode == rtc.PCF8523OffsetEveryMinute)
	must(full.SetOffset(0, rtc.PCF8523OffsetEveryTwoHours))

	// Timer B at 64 Hz from 64 counts expires after ~1 s.
	_, _ = full.PollInterrupt()
	must(full.ConfigureTimerB(64, rtc.PCF8523Clock64Hz, 46.875, false))
	time.Sleep(1500 * time.Millisecond)
	status, err := full.PollInterrupt()
	check("timer_b_fires", err == nil && status&rtc.PCF8523SourceTimerB != 0)
	must(full.DisableTimerB())

	done()
}
