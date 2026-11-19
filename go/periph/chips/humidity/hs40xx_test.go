package humidity

import (
	"errors"
	"testing"
)

func htFrame(h, t uint16) []byte {
	d := []byte{byte(h >> 8), byte(h), byte(t >> 8), byte(t)}
	return append(d, hs40xxCRC8(d))
}

func tOnlyFrame(t uint16) []byte {
	d := []byte{byte(t >> 8), byte(t)}
	return append(d, hs40xxCRC8([]byte{0, 0, d[0], d[1]}))
}

func TestHS40xxCRCDatasheetExample(t *testing.T) {
	if got := hs40xxCRC8([]byte{0x05, 0x80, 0x05, 0x80}); got != 0xF2 {
		t.Errorf("crc = %#x", got)
	}
}

func TestHS40xxConstructorNoBusAccess(t *testing.T) {
	m := &hs30xxMock{}
	NewHS40xxFull(m)
	NewHS40xxMinimal(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
}

func TestHS40xxAliases(t *testing.T) {
	m := &hs30xxMock{}
	var a *HS4001Full = NewHS4001Full(m)
	var b *HS40xxFull = NewHS4003Full(m)
	var c *HS4003Minimal = NewHS4003Minimal(m)
	var d *HS40xxMinimal = NewHS4001Minimal(m)
	if a == nil || b == nil || c == nil || d == nil {
		t.Error("alias constructors")
	}
}

func TestHS40xxReadExampleFrame(t *testing.T) {
	m := &hs30xxMock{}
	m.queue([]byte{0x05, 0x80, 0x05, 0x80, 0xF2})
	temp, rh, err := NewHS40xxMinimal(m).Read()
	if err != nil || !hsWrites(m, []byte{0xF5}) {
		t.Fatalf("Read: %v writes %x", err, m.writes)
	}
	if !hsNear(float64(rh), 8.59, 0.01) || !hsNear(float64(temp), -25.8, 0.05) {
		t.Errorf("Read = %v %v", temp, rh)
	}
}

func TestHS40xxFullScaleAndZero(t *testing.T) {
	m := &hs30xxMock{}
	m.queue(htFrame(0x3FFF, 0x3FFF))
	temp, rh, _ := NewHS40xxMinimal(m).Read()
	if !hsNear(float64(rh), 100, 1e-3) || !hsNear(float64(temp), 125, 1e-3) {
		t.Errorf("full scale = %v %v", temp, rh)
	}
	m.queue(htFrame(0, 0))
	temp, rh, _ = NewHS40xxMinimal(m).Read()
	if rh != 0 || !hsNear(float64(temp), -40, 1e-3) {
		t.Errorf("zero = %v %v", temp, rh)
	}
}

func TestHS40xxNackRetry(t *testing.T) {
	m := &hs30xxMock{nackReads: 2}
	m.queue(htFrame(0x2000, 0x1000))
	_, rh, err := NewHS40xxMinimal(m).Read()
	if err != nil || !hsNear(float64(rh), 50, 0.01) {
		t.Errorf("retry: %v %v", rh, err)
	}
	m = &hs30xxMock{nackReads: 1000}
	if _, _, err := NewHS40xxMinimal(m).Read(); err == nil {
		t.Error("persistent NACK should fail")
	}
}

func TestHS40xxCRCRetry(t *testing.T) {
	bad := htFrame(0x2000, 0x1000)
	bad[4] ^= 0xFF
	m := &hs30xxMock{}
	m.queue(bad)
	m.queue(htFrame(0x2000, 0x1000))
	if _, _, err := NewHS40xxMinimal(m).Read(); err != nil || !hsWrites(m, []byte{0xF5}, []byte{0xF5}) {
		t.Errorf("crc retry: %v %x", err, m.writes)
	}
	m = &hs30xxMock{}
	m.queue(bad)
	m.queue(bad)
	if _, _, err := NewHS40xxMinimal(m).Read(); !errors.Is(err, ErrHS40xxCRC) {
		t.Errorf("crc twice: %v", err)
	}
}

func TestHS40xxSingleValues(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	m.queue(tOnlyFrame(0x1000))
	temp, err := c.ReadTemperature()
	if err != nil || !hsNear(float64(temp), 4096.0/16383*165-40, 1e-3) || !hsWrites(m, []byte{0xF3}) {
		t.Errorf("ReadTemperature %v %v %x", temp, err, m.writes)
	}
	m.queue(htFrame(0x2000, 0x1000))
	if rh, err := c.ReadHumidity(); err != nil || !hsNear(float64(rh), 50, 0.01) {
		t.Errorf("ReadHumidity %v %v", rh, err)
	}
	m.queue(htFrame(0x2000, 0x1000))
	if _, rh, err := c.ReadHold(); err != nil || !hsNear(float64(rh), 50, 0.01) || !m.lastWrite(0xE5) {
		t.Errorf("ReadHold %v %v", rh, err)
	}
	m.queue(htFrame(0x1234, 0x0ABC))
	if h, tt, err := c.ReadRaw(); err != nil || h != 0x1234 || tt != 0x0ABC {
		t.Errorf("ReadRaw %x %x %v", h, tt, err)
	}
}

func TestHS40xxRegisters(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	m.queue([]byte{0x5A})
	if v, err := c.ReadRegister(0x02); err != nil || v != 0x5A || !m.lastWrite(0xA7, 0x02) {
		t.Errorf("ReadRegister %v %v", v, err)
	}
	_ = c.WriteRegister(0x03, 0x0A)
	if !m.lastWrite(0xA6, 0x03, 0x0A) {
		t.Error("WriteRegister")
	}
}

func TestHS40xxResolution(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	m.queue([]byte{0xF3})
	if err := c.SetResolution(8, 12); err != nil || !hsWrites(m, []byte{0xA7, 0}, []byte{0xA6, 0, 0xF2}) {
		t.Errorf("SetResolution %v %x", err, m.writes)
	}
	m = &hs30xxMock{}
	c = NewHS40xxFull(m)
	if err := c.SetResolution(9, 0); !errors.Is(err, ErrHS40xxArgument) || len(m.writes) != 0 {
		t.Errorf("invalid resolution %v", err)
	}
	m.queue([]byte{0x0B})
	if h, tb, err := c.GetResolution(); err != nil || h != 12 || tb != 14 {
		t.Errorf("GetResolution %v %v %v", h, tb, err)
	}
}

func TestHS40xxPeriodicAndAlerts(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	m.queue([]byte{0x00})
	m.queue([]byte{0x01})
	if err := c.StartPeriodic(2.5); err != nil || !hsWrites(m, []byte{0xA7, 3}, []byte{0xA7, 2}, []byte{0xA6, 2, 0xA1}) {
		t.Errorf("StartPeriodic %v %x", err, m.writes)
	}
	if err := c.StartPeriodic(3); !errors.Is(err, ErrHS40xxArgument) {
		t.Error("invalid period")
	}
	m.queue(htFrame(0x2000, 0x1000))
	if p, err := c.ReadPeriodic(); err != nil || p.Status != 0 || !hsNear(float64(p.HumidityPct), 50, 0.01) {
		t.Errorf("ReadPeriodic %v %v", p, err)
	}
	_ = c.StopPeriodic()
	if !m.lastWrite(0x30) {
		t.Error("StopPeriodic")
	}

	m = &hs30xxMock{}
	c = NewHS40xxFull(m)
	if _, err := c.PollInterrupt(); !errors.Is(err, ErrHS40xxAlertsDisabled) {
		t.Errorf("poll without alerts %v", err)
	}
	m.queue([]byte{0x00})
	_ = c.EnableInterrupt(HS40xxSourceRHHigh)
	if !hsWrites(m, []byte{0xA7, 3}, []byte{0xA6, 3, 0x02}) {
		t.Errorf("EnableInterrupt %x", m.writes)
	}
	m.queue(append([]byte{0x0A}, htFrame(0x2000, 0x1000)...))
	if p, err := c.ReadPeriodic(); err != nil || p.Status != 0x0A {
		t.Errorf("status %v %v", p, err)
	}
	m.queue(append([]byte{0x02}, htFrame(0x2000, 0x1000)...))
	if s, err := c.PollInterrupt(); err != nil || s != 0x02 {
		t.Errorf("PollInterrupt %v %v", s, err)
	}
	m.queue([]byte{0x02})
	_ = c.DisableInterrupt(HS40xxSourceRHHigh)
	if !m.lastWrite(0xA6, 3, 0x00) {
		t.Error("DisableInterrupt")
	}
}

func TestHS40xxThresholds(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	a, b := float32(60), float32(55)
	_ = c.SetAlertThresholds(HS40xxThresholds{RHHigh: &a, RHHighReset: &b})
	if !hsWrites(m, []byte{0xA6, 0x0F, 0x66}, []byte{0xA6, 0x10, 0x26}, []byte{0xA6, 0x0D, 0x33}, []byte{0xA6, 0x0E, 0x23}) {
		t.Errorf("rh thresholds %x", m.writes)
	}
	m = &hs30xxMock{}
	c = NewHS40xxFull(m)
	hi, lo := float32(125), float32(-40)
	_ = c.SetAlertThresholds(HS40xxThresholds{TempHigh: &hi, TempLow: &lo})
	if !hsWrites(m, []byte{0xA6, 0x07, 0xFF}, []byte{0xA6, 0x08, 0x3F}, []byte{0xA6, 0x09, 0x00}, []byte{0xA6, 0x0A, 0x00}) {
		t.Errorf("temp thresholds %x", m.writes)
	}
}

func TestHS40xxPolarityIDReset(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	m.queue([]byte{0xB1})
	_ = c.SetAlertPolarity(false)
	if !m.lastWrite(0xA6, 2, 0xB0) {
		t.Errorf("SetAlertPolarity %x", m.writes)
	}
	m.queue([]byte{0xDE, 0xAD, 0xBE, 0xEF})
	if id, err := c.ReadID(); err != nil || id != 0xDEADBEEF {
		t.Errorf("ReadID %x %v", id, err)
	}
	m = &hs30xxMock{}
	c = NewHS40xxFull(m)
	if err := c.SoftReset(); err != nil || !hsWrites(m, []byte{0x30}, []byte{0xFE}) {
		t.Errorf("SoftReset %v %x", err, m.writes)
	}
}

func TestHS40xxDewPoint(t *testing.T) {
	m := &hs30xxMock{}
	c := NewHS40xxFull(m)
	h, tt := 0.5*16383.0, (25.0+40.0)/165.0*16383.0
	m.queue(htFrame(uint16(h), uint16(tt)))
	if dp, err := c.DewPoint(); err != nil || !hsNear(float64(dp), 13.86, 0.1) {
		t.Errorf("DewPoint %v %v", dp, err)
	}
	m.queue(htFrame(0, 0x1000))
	if dp, _ := c.DewPoint(); !hsNear(float64(dp), -273.15, 1e-3) {
		t.Errorf("DewPoint zero humidity %v", dp)
	}
}
