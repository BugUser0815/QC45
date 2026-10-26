import binascii
import io
import json
import os
import socket
import struct
import sys
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import autocharge_agent as agent

LOCAL = binascii.unhexlify('0200000000aa')
REMOTE = binascii.unhexlify('020000000001')
IP_CAR = b'\xfe\x80' + b'\x00' * 13 + b'\x01'
IP_STATION = b'\xfe\x80' + b'\x00' * 13 + b'\xaa'
with open(os.path.join(os.path.dirname(__file__), 'fixtures.json')) as source:
    FIXTURES = dict((item['name'], item) for item in json.load(source)['messages'])


def exi(name):
    return binascii.unhexlify(FIXTURES[name]['exi'])


def v2g(payload):
    return struct.pack('!BBHI', 1, 254, 0x8001, len(payload)) + payload


def packet(sequence=100, flags=16, data=b'', inbound=True, vlan=False, extension=False):
    src, dst = (IP_CAR, IP_STATION) if inbound else (IP_STATION, IP_CAR)
    sport, dport = (49153, 50000) if inbound else (50000, 49153)
    tcp = struct.pack('!HHIIBBHHH', sport, dport, sequence, 1, 0x50, flags, 4096, 0, 0) + data
    if extension:
        tcp = b'\x06\x00' + b'\x00' * 6 + tcp
    ip = struct.pack('!IHBB', 6 << 28, len(tcp), 0 if extension else 6, 64) + src + dst + tcp
    addresses = LOCAL + REMOTE if inbound else REMOTE + LOCAL
    ethernet = b'\x81\x00\x00\x01\x86\xdd' if vlan else b'\x86\xdd'
    return addresses + ethernet + ip


class ObserverTest(unittest.TestCase):
    def setUp(self):
        self.now = 10
        self.observer = agent.Observer(LOCAL, lambda: self.now)
        self.sap = v2g(exi('sap'))
        self.setup = v2g(exi('din-short'))

    def connect(self, sequence=100):
        self.observer.packet(packet(sequence, 2))

    def identify(self):
        self.connect()
        return self.observer.packet(packet(101, data=self.sap + self.setup))

    def test_synthetic_codec_fixtures_decode_and_other_protocols_fail_closed(self):
        self.assertEqual('020000000001', agent.session_setup_evccid(exi('din-short')))
        self.assertEqual('020000000002', agent.session_setup_evccid(exi('din-eight')))
        self.assertIsNone(agent.session_setup_evccid(exi('iso2')))
        self.assertIsNone(agent.session_setup_evccid(exi('din-resume')))
        self.assertIsNone(agent.session_setup_evccid(exi('sap')))
        for length in range(len(exi('din-short'))):
            self.assertIsNone(agent.session_setup_evccid(exi('din-short')[:length]))
        self.assertIsNone(agent.session_setup_evccid(exi('din-short') + b'\x00'))

    def test_each_tcp_segmentation_including_bytewise_delivery(self):
        data = self.sap + self.setup
        for split in range(1, len(data)):
            self.observer.reset()
            self.connect()
            self.assertIsNone(self.observer.packet(packet(101, data=data[:split])))
            self.assertEqual('020000000001', self.observer.packet(packet(101 + split, data=data[split:])))
        self.observer.reset()
        self.connect()
        for index in range(len(data)):
            found = self.observer.packet(packet(101 + index, data=data[index:index + 1]))
        self.assertEqual('020000000001', found)

    def test_out_of_order_and_overlapping_retransmission(self):
        data = self.sap + self.setup
        self.connect()
        self.observer.packet(packet(111, data=data[10:]))
        self.assertEqual('020000000001', self.observer.packet(packet(101, data=data[:15])))
        token = self.observer.current()[0]
        self.observer.packet(packet(101, data=data))
        self.assertEqual(token, self.observer.current()[0])

    def test_missed_syn_never_identifies_existing_session(self):
        self.assertIsNone(self.observer.packet(packet(101, data=self.sap + self.setup)))
        self.assertIsNone(self.observer.current())

    def test_syn_retransmission_does_not_create_new_authorization_token(self):
        self.identify()
        token = self.observer.current()[0]
        self.observer.packet(packet(100, 2))
        self.assertEqual(token, self.observer.current()[0])

    def test_new_tcp_connection_gets_new_token_not_the_v2g_sessionid(self):
        self.identify()
        old = self.observer.current()[0]
        self.connect(200)
        self.observer.packet(packet(201, data=self.sap + self.setup))
        self.assertNotEqual(old, self.observer.current()[0])

    def test_reset_on_receiver_reconnect_requires_new_syn(self):
        self.identify()
        self.observer.reset()
        self.observer.packet(packet(101, data=self.sap + self.setup))
        self.assertIsNone(self.observer.current())

    def test_rst_fin_and_staleness_remove_identity(self):
        for flags in (1, 4):
            self.identify()
            self.observer.packet(packet(999, flags, inbound=False))
            self.assertIsNone(self.observer.current())
        self.identify()
        self.now += 2.01
        self.assertIsNone(self.observer.current())

    def test_station_payload_does_not_keep_vehicle_alive(self):
        self.identify()
        self.now += 2.01
        self.observer.packet(packet(999, data=b'anything', inbound=False))
        self.assertIsNone(self.observer.current())

    def test_vlan_and_ipv6_extension_headers(self):
        for option in ('vlan', 'extension'):
            self.observer.reset()
            options = {option: True}
            self.observer.packet(packet(100, 2, **options))
            self.assertEqual('020000000001', self.observer.packet(packet(101, data=self.sap + self.setup, **options)))

    def test_tls_and_malformed_v2gtp_never_identify(self):
        for payload in (b'\x16\x03\x03' + b'\x00' * 40,
                        b'\x01\xfe\x80\x01\xff\xff\xff\xff', self.setup):
            self.observer.reset()
            self.connect()
            self.observer.packet(packet(101, data=payload))
            self.assertIsNone(self.observer.current())

    def test_truncated_packet_and_foreign_interface_are_ignored(self):
        full = packet(101, data=self.sap + self.setup)
        for length in range(len(full)):
            self.assertIsNone(agent.tcp_packet(full[:length], LOCAL))
        self.assertIsNone(agent.tcp_packet(full, b'\x01' * 6))

    def test_sequence_wrap(self):
        start = 0xfffffff0
        self.connect(start)
        data = self.sap + self.setup
        self.observer.packet(packet(start + 1, data=data[:20]))
        self.assertEqual('020000000001', self.observer.packet(packet((start + 21) & 0xffffffff, data=data[20:])))

    def test_pcap_endianness_and_truncation(self):
        frame = packet()
        for order, magic in (('<', b'\xd4\xc3\xb2\xa1'), ('>', b'\xa1\xb2\xc3\xd4')):
            data = magic + struct.pack(order + 'HHiiii', 2, 4, 0, 0, 65535, 1)
            data += struct.pack(order + 'IIII', 1, 0, len(frame), len(frame)) + frame
            self.assertEqual([frame], list(agent.pcap_packets(io.BytesIO(data))))
            with self.assertRaises(ValueError):
                list(agent.pcap_packets(io.BytesIO(data[:-1])))

    def test_real_observation_link_authenticates_and_discards_old_vehicle(self):
        self.identify()
        old_token = self.observer.current()[0]
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        listener.settimeout(3)
        stop = threading.Event()
        key = b'\x00' * 32
        publisher = threading.Thread(target=agent.publish, args=(self.observer,
            {'host': '127.0.0.1', 'port': listener.getsockname()[1]}, key, stop))
        publisher.daemon = True
        publisher.start()
        connection = None
        stream = None
        try:
            connection, address = listener.accept()
            connection.settimeout(3)
            stream = connection.makefile('rb')
            connection.sendall(b'QC45-AUTOCHARGE/1 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n')
            expected = agent.hmac.new(key,
                b'QC45-AUTOCHARGE/1\n0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                agent.hashlib.sha256).hexdigest()
            self.assertEqual(('AUTH ' + expected + '\n').encode('ascii'), stream.readline())
            connection.sendall(b'OK\n')
            self.assertEqual(b'IDLE\n', stream.readline())
            self.assertIsNone(self.observer.current())
            self.identify()
            new_token = self.observer.current()[0]
            self.assertNotEqual(old_token, new_token)
            self.assertEqual(('VEHICLE ' + new_token + ' 020000000001\n').encode('ascii'), stream.readline())
            self.observer.reset()
            self.assertEqual(b'IDLE\n', stream.readline())
        finally:
            stop.set()
            publisher.join(2)
            if stream is not None:
                stream.close()
            if connection is not None:
                connection.close()
            listener.close()
        self.assertFalse(publisher.is_alive())


if __name__ == '__main__':
    unittest.main()
