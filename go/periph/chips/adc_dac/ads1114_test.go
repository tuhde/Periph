package adcdac

import "testing"

func TestADS1114SingleInputAndGain(t *testing.T) {
	m := newADSConn()
	min, _ := NewADS1114Minimal(m)
	m.set(0, 0x1000)
	if raw, _ := min.ReadRaw(); raw != 0x1000 || m.lastConfig() != 0x8583 {
		t.Errorf("raw %d config 0x%04X", raw, m.lastConfig())
	}
	d, _ := NewADS1114Full(m)
	d.SetGain(ADS1114PGA0256)
	if d.LSB() != 0.256/32768 {
		t.Errorf("lsb %v", d.LSB())
	}
	d.StartContinuous()
	if m.lastConfig() != 0x0A83 {
		t.Errorf("continuous config 0x%04X", m.lastConfig())
	}
}

func TestADS1114ComparatorAndReset(t *testing.T) {
	m := newADSConn()
	d, _ := NewADS1114Full(m)
	d.SetGain(ADS1114PGA0256)
	d.SetThresholds(-0.1, 0.1)
	if m.get(2) != 0xCE00 || m.get(3) != 0x3200 {
		t.Errorf("thresholds %04X %04X", m.get(2), m.get(3))
	}
	d.ConfigureComparator(ADS1114CompTraditional, ADS1114ActiveLow, false, ADS1114Queue1)
	if m.lastConfig()&3 != 0 {
		t.Error("comparator not enabled")
	}
	d.Reset()
	if m.get(1) != 0x8583 || d.GetGain() != 2 {
		t.Error("reset failed")
	}
}
