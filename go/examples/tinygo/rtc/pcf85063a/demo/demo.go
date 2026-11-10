//go:build tinygo

// PCF85063A demo example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, rtc.PCF85063AAddress, nil, nil) // Create I2C connection, (i2c, addr=0x51) → *I2CConnection
	defer conn.Close()
	machine.LED.Configure(machine.PinConfig{Mode: machine.PinOutput})

	clock, err := rtc.NewPCF85063AFull(conn) // Create PCF85063A Full driver, (conn) → (*PCF85063AFull, error)
	must(err)

	// --- Detect a lost time reference and reseed if needed ---
	// A fresh chip, or one whose supply dropped out, reports the OS flag set:
	// its calendar cannot be trusted until it is reseeded. The crystal needs
	// up to 2 s to start, so wait until the flag stays clear.
	stopped, err := clock.OscillatorStopped() // Query oscillator-stop flag, () → (bool, error)
	must(err)
	if stopped {
		must(clock.SetDatetime(2026, 1, 1, 4, 0, 0, 0)) // Set clock/calendar, (year, month, day, weekday 0=Sun, hour, minute, second) → error
		fmt.Println("oscillator was stopped - reseeded from reference timestamp")
		for stopped {
			time.Sleep(100 * time.Millisecond)
			stopped, err = clock.OscillatorStopped() // Query oscillator-stop flag, () → (bool, error)
			must(err)
		}
	}

	// --- Count boots in the free RAM byte ---
	// The RAM byte survives as long as VDD is present, so it works as a tiny
	// non-volatile counter next to the clock.
	ram, err := clock.ReadRAM() // Read RAM byte, () → (uint8, error)
	must(err)
	boots := ram + 1
	must(clock.WriteRAM(boots))                                           // Write RAM byte, (value 0–255) → error
	year, month, day, _, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
	must(err)
	offset, _, err := clock.GetOffset() // Read offset calibration, () → (int8, PCF85063AOffsetMode, error)
	must(err)
	fmt.Printf("boot #%d %04d-%02d-%02d %02d:%02d:%02d offset=%d\n", boots, year, month, day, hour, minute, second, offset)

	// --- Tune the board for low power ---
	// Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
	// off — it is enabled at power-on and costs current on a battery device.
	must(clock.SetOscillatorLoad(12.5)) // Select crystal load, (picofarads 7|12.5) → error
	must(clock.DisableClockOutput())    // Disable CLKOUT, () → error

	// --- Hourly wake-up plus a 30 s heartbeat ---
	// Only second and minute are enabled, so the alarm matches at hh:00:00
	// every hour. The pulsed timer reloads automatically; minute ticks share
	// the same TF flag and are reported as PCF85063ASourceTimer.
	must(clock.SetAlarm(rtc.PCF85063AAlarm{Second: 0, Minute: 0, Hour: rtc.PCF85063AAlarmDisabled, Day: rtc.PCF85063AAlarmDisabled, Weekday: rtc.PCF85063AAlarmDisabled})) // Configure alarm, (PCF85063AAlarm{Second, Minute, Hour, Day, Weekday}) → error
	must(clock.ConfigureTimer(30, rtc.PCF85063AClock1Hz, true))                                                                                                            // Start countdown timer, (value 1–255, sourceClock, pulsed) → error
	must(clock.SetMinuteInterrupt(rtc.PCF85063AMinuteEvery))                                                                                                               // Select minute interrupt, (mode Off|Every|Half) → error

	// --- Dispatch by source: log on the alarm, blink on the heartbeat ---
	alarms := make(chan struct{}, 8)
	ledOn := false
	must(clock.OnInterrupt(func(status uint8) { // Subscribe to interrupts, (callback) → error
		if status&rtc.PCF85063ASourceAlarm != 0 {
			year, month, day, _, hour, minute, second, err := clock.GetDatetime() // Read clock/calendar, () → (year, month, day, weekday, hour, minute, second int, error)
			if err == nil {
				fmt.Printf("[hourly] %04d-%02d-%02d %02d:%02d:%02d\n", year, month, day, hour, minute, second)
			}
			alarms <- struct{}{}
		}
		if status&rtc.PCF85063ASourceTimer != 0 {
			ledOn = !ledOn
			machine.LED.Set(ledOn)
		}
	}))
	must(clock.EnableInterrupt(rtc.PCF85063ASourceAlarm | rtc.PCF85063ASourceTimer)) // Enable sources, (source) → error

	timeout := time.After(5 * time.Minute)
	for n := 0; n < 5; {
		select {
		case <-alarms:
			n++
		case <-timeout:
			n = 5
		}
	}

	// --- Leave the chip quiet on exit ---
	must(clock.DisableInterrupt(rtc.PCF85063ASourceAlarm | rtc.PCF85063ASourceTimer)) // Disable sources, (source) → error
	must(clock.DisableTimer())                                                        // Stop timer, () → error
	must(clock.SetMinuteInterrupt(rtc.PCF85063AMinuteOff))                            // Select minute interrupt, (mode Off|Every|Half) → error
	must(clock.OffInterrupt())                                                        // Unsubscribe, () → error
}
