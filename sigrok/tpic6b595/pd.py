import sigrokdecode as srd

ANN_LATCH       = 0
ANN_DRAIN_ON    = 1
ANN_DRAIN_OFF   = 2
ANN_CLEARED     = 3
ANN_WARNING     = 4


def _device_state_label(values):
    """Return "DRAIN0=ON DRAIN1=OFF ..." for one device."""
    return ' '.join('DRAIN%d=%s' % (i, 'ON' if (values >> i) & 1 else 'off')
                    for i in range(7, -1, -1))


class Decoder(srd.Decoder):
    api_version = 3
    id = 'tpic6b595'
    name = 'TPIC6B595'
    longname = 'Texas Instruments TPIC6B595 8-bit power SiPo shift register'
    desc = ('Annotate per-cascaded-device DRAIN0–DRAIN7 ON/OFF state from '
            'the `sipo` transport decoder\'s LATCH/CLEAR packets.')
    license = 'gplv2+'
    inputs = ['sipo']
    outputs = ['tpic6b595']
    tags = ['IC', 'Power', 'IO']

    options = (
        {'id': 'num_devices', 'desc': 'Number of cascaded TPIC6B595s (default 1)',
         'default': 1},
    )
    annotations = (
        ('latch',     'Latch'),
        ('drain-on',  'DRAIN ON'),
        ('drain-off', 'DRAIN OFF'),
        ('cleared',   'Cleared'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_LATCH, ANN_DRAIN_ON, ANN_DRAIN_OFF)),
        ('status',   'Status',   (ANN_CLEARED,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def metadata(self, key, value):
        if key == srd.SRD_CONF_NUM_DEVICES:
            self.num_devices = int(value)
        elif key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def reset(self):
        self.num_devices = 1

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, payload = data

        if ptype == 'LATCH':
            num_devices = self.num_devices
            if num_devices <= 0:
                num_devices = 1
            # Reverse back into per-device order: device 0 = nearest the
            # controller (was shifted in last on the wire, so is first
            # in the payload when read MSB-first through the cascade).
            payload_bytes = payload
            n = len(payload_bytes)
            if n == 0 or n % num_devices != 0:
                self.put(ss, es, self.out_ann,
                         [ANN_WARNING, [
                             ('LATCH length %d is not a multiple of '
                              'num_devices=%d' % (n, num_devices)),
                             ('bad_latch:%d/%d' % (n, num_devices)),
                         ]])
                return
            devices = []
            for i in range(0, n, num_devices):
                devices.append(payload_bytes[i:i + num_devices])
            # 'devices' is now an array of cascaded values, indexed by
            # device position from nearest the controller to farthest.
            for dev_idx, vals in enumerate(devices):
                byte = vals[0] if isinstance(vals, (bytes, bytearray)) else vals
                self.put(ss, es, self.out_ann,
                         [ANN_LATCH, [
                             ('Device %d: 0x%02X — %s' % (
                                 dev_idx, byte, _device_state_label(byte))),
                             ('D%d:0x%02X' % (dev_idx, byte)),
                             ('0x%02X' % byte),
                         ]])
                for bit in range(8):
                    if (byte >> bit) & 1:
                        self.put(ss, es, self.out_ann,
                                 [ANN_DRAIN_ON, [
                                     ('DRAIN%d ON' % bit),
                                     ('D%d' % bit),
                                     ('1' if bit == 7 else ''),
                                 ]])
                    else:
                        self.put(ss, es, self.out_ann,
                                 [ANN_DRAIN_OFF, [
                                     ('DRAIN%d off' % bit),
                                     ('d%d' % bit),
                                     ('0' if bit == 7 else ''),
                                 ]])

        elif ptype == 'CLEAR':
            self.put(ss, es, self.out_ann,
                     [ANN_CLEARED, [
                         'Shift register cleared (SRCLR)',
                         'Cleared',
                         'CLR',
                     ]])

        else:
            self.put(ss, es, self.out_ann,
                     [ANN_WARNING, [
                         ('Unknown sipo packet: %r' % (ptype,)),
                         ('unknown:%s' % ptype),
                         '?',
                     ]])
