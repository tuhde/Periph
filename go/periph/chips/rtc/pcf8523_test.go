package rtc

import (
	"bytes"
	"testing"
)

// newPCF8523Full builds a PCF8523Full over the FIFO mock, consuming the
// constructor's presence read.
func newPCF8523Full(t *testing.T) (*PCF8523Full, *mockConnection) {
	t.Helper()
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // CONTROL_1 presence check
	d, err := NewPCF8523Full(conn)
	if err != nil {
		t.Fatalf("NewPCF8523Full: %v", err)
	}
	return d, conn
}

func TestPCF8523ConstructSetsBatteryStandard(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x00})
	if _, err := NewPCF8523Minimal(conn); err != nil {
		t.Fatalf("NewPCF8523Minimal: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl3, 0x00}) {
		t.Errorf("constructor: want CONTROL_3=0x00, got %v", conn.lastWrite())
	}
}

func TestPCF8523GetSetDatetime(t *testing.T) {
	d, conn := newPCF8523Full(t)

	// 2026-09-23 Wednesday(3) 14:30:45, OS flag set is masked off.
	conn.queueRead([]byte{0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26})
	year, month, day, weekday, hour, minute, second, err := d.GetDatetime()
	if err != nil {
		t.Fatalf("GetDatetime: %v", err)
	}
	if year != 2026 || month != 9 || day != 23 || weekday != 3 || hour != 14 || minute != 30 || second != 45 {
		t.Errorf("GetDatetime: got %d-%d-%d wd=%d %d:%d:%d", year, month, day, weekday, hour, minute, second)
	}

	conn.queueRead([]byte{0x08}) // CONTROL_1 with 12_24 set
	start := len(conn.writes)
	if err := d.SetDatetime(2026, 9, 23, 3, 14, 30, 45); err != nil {
		t.Fatalf("SetDatetime: %v", err)
	}
	w := conn.writes[start:]
	want := [][]byte{
		{pcf8523RegControl1},
		{pcf8523RegControl1, 0x20},
		{pcf8523RegSeconds, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26},
		{pcf8523RegControl1, 0x00},
	}
	if len(w) != len(want) {
		t.Fatalf("SetDatetime: got %d writes %v", len(w), w)
	}
	for i := range want {
		if !bytes.Equal(w[i], want[i]) {
			t.Errorf("SetDatetime write %d: got %v, want %v", i, w[i], want[i])
		}
	}
}

func TestPCF8523Alarm(t *testing.T) {
	d, conn := newPCF8523Full(t)
	a := PCF8523Alarm{Minute: 45, Hour: PCF8523AlarmDisabled, Day: PCF8523AlarmDisabled, Weekday: 6}
	if err := d.SetAlarm(a); err != nil {
		t.Fatalf("SetAlarm: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegMinuteAlarm, 0x45, 0x80, 0x80, 0x06}) {
		t.Errorf("SetAlarm: got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0x45, 0x80, 0x80, 0x06})
	got, err := d.GetAlarm()
	if err != nil || got != a {
		t.Errorf("GetAlarm: got %+v, %v", got, err)
	}
}

func TestPCF8523Offset(t *testing.T) {
	d, conn := newPCF8523Full(t)
	if err := d.SetOffset(-64, PCF8523OffsetEveryMinute); err != nil {
		t.Fatalf("SetOffset: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegOffset, 0xC0}) {
		t.Errorf("SetOffset: got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0xC0})
	if off, mode, _ := d.GetOffset(); off != -64 || mode != PCF8523OffsetEveryMinute {
		t.Errorf("GetOffset: got %d %d", off, mode)
	}
	conn.queueRead([]byte{0x3F})
	if off, mode, _ := d.GetOffset(); off != 63 || mode != PCF8523OffsetEveryTwoHours {
		t.Errorf("GetOffset positive: got %d %d", off, mode)
	}
}

func TestPCF8523Timers(t *testing.T) {
	d, conn := newPCF8523Full(t)
	conn.queueRead([]byte{0x38}) // TMR_CLKOUT_CTRL POR value
	if err := d.ConfigureTimerA(PCF8523TimerAWatchdog, 5, PCF8523Clock1Hz, true); err != nil {
		t.Fatalf("ConfigureTimerA: %v", err)
	}
	for _, w := range [][]byte{{pcf8523RegTmrAFreqCtrl, 0x02}, {pcf8523RegTmrAReg, 5}, {pcf8523RegTmrClkoutCtrl, 0xBC}} {
		if !conn.writesContain(w) {
			t.Errorf("ConfigureTimerA: missing write %v", w)
		}
	}
	// EnableInterrupt(TimerA) in watchdog mode selects WTAIE.
	conn.queueRead([]byte{0xBC}) // TMR_CLKOUT_CTRL
	conn.queueRead([]byte{0x00}) // CONTROL_2
	if err := d.EnableInterrupt(PCF8523SourceTimerA); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl2, 0x7C}) {
		t.Errorf("EnableInterrupt TimerA: got %v", conn.lastWrite())
	}

	conn.queueRead([]byte{0x38})
	if err := d.ConfigureTimerB(30, PCF8523Clock1_60Hz, 130, false); err != nil {
		t.Fatalf("ConfigureTimerB: %v", err)
	}
	for _, w := range [][]byte{{pcf8523RegTmrBFreqCtrl, 0x43}, {pcf8523RegTmrBReg, 30}, {pcf8523RegTmrClkoutCtrl, 0x39}} {
		if !conn.writesContain(w) {
			t.Errorf("ConfigureTimerB: missing write %v", w)
		}
	}

	conn.queueRead([]byte{0x00})
	if err := d.SetClockOutput(1); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegTmrClkoutCtrl, 0x30}) {
		t.Errorf("SetClockOutput: got %v, %v", conn.lastWrite(), err)
	}
}

func TestPCF8523BatteryBackup(t *testing.T) {
	d, conn := newPCF8523Full(t)
	conn.queueRead([]byte{0x00})
	if err := d.ConfigureBatteryBackup(PCF8523BatteryDirect, false); err != nil {
		t.Fatalf("ConfigureBatteryBackup: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl3, 0xA8}) {
		t.Errorf("ConfigureBatteryBackup: got %v", conn.lastWrite())
	}
}

func TestPCF8523PollInterrupt(t *testing.T) {
	d, conn := newPCF8523Full(t)
	// WTAF, CTBF, AF set with CTAIE|CTBIE enabled; BSF, BLF, BSIE.
	conn.queueRead([]byte{0xAB, 0x0E})
	status, err := d.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	want := PCF8523SourceTimerA | PCF8523SourceTimerB | PCF8523SourceAlarm | PCF8523SourceBatterySwitch | PCF8523SourceBatteryLow
	if status != want {
		t.Errorf("PollInterrupt: got 0x%02X, want 0x%02X", status, want)
	}
	if !conn.writesContain([]byte{pcf8523RegControl2, 0x53}) {
		t.Errorf("PollInterrupt: CONTROL_2 clear write missing: %v", conn.writes)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl3, 0x02}) {
		t.Errorf("PollInterrupt: CONTROL_3 clear write: got %v", conn.lastWrite())
	}
}

func TestPCF8523EnableSecondAlarmAndSoftwareReset(t *testing.T) {
	d, conn := newPCF8523Full(t)
	conn.queueRead([]byte{0x00}) // CONTROL_1
	if err := d.EnableInterrupt(PCF8523SourceSecond | PCF8523SourceAlarm); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl1, 0x06}) {
		t.Errorf("EnableInterrupt: got %v", conn.lastWrite())
	}
	if err := d.SoftwareReset(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf8523RegControl1, 0x58}) {
		t.Errorf("SoftwareReset: got %v, %v", conn.lastWrite(), err)
	}
}
