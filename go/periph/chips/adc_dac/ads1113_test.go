package adcdac

import "testing"

func TestADS1113FixedRangeContinuousAndReset(t *testing.T) {
	m := newADSConn()
	min, _ := NewADS1113Minimal(m)
	m.set(0, 0x0200)
	if v, _ := min.ReadVoltage(); v != float32(0x0200)*0.0000625 {
		t.Errorf("voltage %v", v)
	}
	d, _ := NewADS1113Full(m)
	if d.FullScale() != 2.048 || d.LSB() != 2.048/32768 {
		t.Errorf("scale %v %v", d.FullScale(), d.LSB())
	}
	d.SetDataRate(ADS1113DR8)
	d.StartContinuous()
	if m.lastConfig() != 0x0403 {
		t.Errorf("continuous config 0x%04X", m.lastConfig())
	}
	d.Reset()
	if m.get(1) != 0x8583 || d.DataRateSps() != 128 {
		t.Error("reset failed")
	}
}
