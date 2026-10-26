#!/usr/bin/env python
"""Read-only DIN SessionSetup observer for EVAcharge SE (Python 2.7 / 3).

No CAN/PLC writes and no charging authorization on the controller. Only the
configured Geode receiver may request a fresh OCPP authorization. The narrow
EXI grammar follows DIN 70121:2012 SessionSetupReq; other messages/protocols,
signed headers, TLS and TCP sessions missed at startup produce no identity.
"""
from __future__ import print_function

import argparse
import binascii
import hashlib
import hmac
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid


def monotonic():
    if hasattr(time, 'monotonic'):
        return time.monotonic()
    with open('/proc/uptime', 'r') as stream:
        return float(stream.read().split()[0])


def hex_bytes(data):
    return binascii.hexlify(data).decode('ascii').upper()


class Bits(object):
    def __init__(self, data):
        self.data = bytearray(data)
        self.at = 0

    def read(self, count):
        if self.at + count > len(self.data) * 8:
            raise ValueError('truncated EXI')
        value = 0
        for unused in range(count):
            value = (value << 1) | ((self.data[self.at // 8] >> (7 - self.at % 8)) & 1)
            self.at += 1
        return value

    def expect(self, count, value):
        if self.read(count) != value:
            raise ValueError('unsupported EXI grammar')

    def binary(self, minimum, maximum):
        self.expect(1, 0)  # CH
        size = self.read(8)  # bounded lengths fit one EXI unsigned octet
        if not minimum <= size <= maximum:
            raise ValueError('unsupported binary length')
        result = bytearray(self.read(8) for unused in range(size))
        self.expect(1, 0)  # EE
        return result


def session_setup_evccid(data):
    """Decode only a complete, unsigned DIN SessionSetupReq, never byte search.

    Grammar reference: EVerest/libcbv2g din_msgDefDecoder.c (root 77, body 29).
    Independent implementation; checked against EXIficient-generated messages.
    """
    try:
        bits = Bits(data)
        bits.expect(8, 0x80)  # EXI version 1, bit packed, no options
        bits.expect(7, 77)  # DIN V2G_Message
        bits.expect(1, 0)  # Header
        bits.expect(1, 0)  # SessionID
        session = bits.binary(1, 8)
        if any(session):  # no automatic authorization of session resumption
            return None
        bits.expect(2, 2)  # Header EE; no Notification / Signature
        bits.expect(1, 0)  # Body
        bits.expect(6, 29)  # SessionSetupReq
        bits.expect(1, 0)  # EVCCID
        identity = bits.binary(6, 6)
        bits.expect(1, 0)  # SessionSetupReq EE
        bits.expect(1, 0)  # Body EE
        bits.expect(1, 0)  # V2G_Message EE
        padding = len(data) * 8 - bits.at
        if padding > 7 or bits.read(padding) != 0:
            return None
        if identity[0] & 1 or not any(identity) or all(x == 255 for x in identity):
            return None
        return hex_bytes(identity)
    except (ValueError, IndexError):
        return None


class Flow(object):
    def __init__(self, sequence, now):
        self.initial = sequence
        self.next = (sequence + 1) & 0xffffffff
        self.pending = {}
        self.buffer = b''
        self.messages = 0
        self.identity = None
        self.token = uuid.uuid4().hex
        self.updated = now
        self.disabled = False

    def receive(self, sequence, data, now):
        if self.disabled or not data:
            return None
        self.updated = now
        if self.identity:
            return None
        distance = (sequence - self.next) & 0xffffffff
        if distance >= 0x80000000:
            overlap = (self.next - sequence) & 0xffffffff
            if overlap >= len(data):
                return None
            data = data[overlap:]
            sequence = self.next
            distance = 0
        if distance > 32768 or len(self.pending) >= 16:
            self.disabled = True
            return None
        if distance:
            previous = self.pending.get(sequence)
            if previous is not None and previous != data:
                self.disabled = True
            self.pending[sequence] = data
            return None
        self.buffer += data
        self.next = (self.next + len(data)) & 0xffffffff
        # Drain segments, including overlapping retransmissions.
        while self.pending:
            keys = sorted(self.pending, key=lambda seq: (seq - self.next) & 0xffffffff)
            progressed = False
            for seq in keys:
                delta = (seq - self.next) & 0xffffffff
                chunk = self.pending[seq]
                if delta == 0 or delta >= 0x80000000:
                    del self.pending[seq]
                    overlap = (self.next - seq) & 0xffffffff
                    if overlap < len(chunk):
                        self.buffer += chunk[overlap:]
                        self.next = (self.next + len(chunk) - overlap) & 0xffffffff
                    progressed = True
                    break
            if not progressed:
                break
        if len(self.buffer) > 65536:
            self.disabled = True
            return None
        while len(self.buffer) >= 8:
            version, inverse, kind, size = struct.unpack('!BBHI', self.buffer[:8])
            if (version, inverse, kind) != (1, 254, 0x8001) or size > 8192 or size < 1:
                self.disabled = True
                return None
            if len(self.buffer) < 8 + size:
                break
            data = self.buffer[8:8 + size]
            self.buffer = self.buffer[8 + size:]
            self.messages += 1
            if self.messages == 1:
                if not data.startswith(b'\x80\x00'):
                    self.disabled = True
                    return None
            elif self.messages == 2:
                self.identity = session_setup_evccid(data)
                self.buffer = b''
                self.pending.clear()
                self.disabled = self.identity is None
                return self.identity
            else:
                self.disabled = True
                return None
        return None


def tcp_packet(packet, local_mac):
    if len(packet) < 14:
        return None
    target, source = packet[:6], packet[6:12]
    kind = struct.unpack('!H', packet[12:14])[0]
    offset = 14
    for unused in range(2):
        if kind not in (0x8100, 0x88a8):
            break
        if len(packet) < offset + 4:
            return None
        kind = struct.unpack('!H', packet[offset + 2:offset + 4])[0]
        offset += 4
    if kind != 0x86dd or len(packet) < offset + 40:
        return None
    header = bytearray(packet[offset:offset + 40])
    if header[0] >> 4 != 6:
        return None
    end = offset + 40 + struct.unpack('!H', packet[offset + 4:offset + 6])[0]
    if end > len(packet):
        return None
    src_ip, dst_ip = packet[offset + 8:offset + 24], packet[offset + 24:offset + 40]
    following = header[6]
    offset += 40
    for unused in range(4):
        if following not in (0, 43, 60):
            break
        if offset + 2 > end:
            return None
        extension = bytearray(packet[offset:offset + 2])
        following = extension[0]
        offset += (extension[1] + 1) * 8
    if following != 6 or offset + 20 > end:
        return None
    tcp = bytearray(packet[offset:offset + 20])
    sport, dport, sequence = struct.unpack('!HHI', packet[offset:offset + 8])
    length = (tcp[12] >> 4) * 4
    if length < 20 or offset + length > end:
        return None
    if target == local_mac and source != local_mac:
        key = (src_ip, dst_ip, sport, dport)
        inbound = True
    elif source == local_mac and target != local_mac:
        key = (dst_ip, src_ip, dport, sport)
        inbound = False
    else:
        return None
    return key, inbound, sequence, tcp[13], packet[offset + length:end]


class Observer(object):
    def __init__(self, local_mac, clock=monotonic):
        self.local_mac = local_mac
        self.clock = clock
        self.lock = threading.Lock()
        self.flows = {}

    def reset(self):
        with self.lock:
            self.flows.clear()

    def packet(self, data):
        parsed = tcp_packet(data, self.local_mac)
        if parsed is None:
            return None
        key, inbound, sequence, flags, payload = parsed
        now = self.clock()
        with self.lock:
            for old in list(self.flows):
                if now - self.flows[old].updated > 10:
                    del self.flows[old]
            if flags & 5:  # FIN or RST, in either direction
                self.flows.pop(key, None)
                return None
            if inbound and flags & 2 and not flags & 16:
                existing = self.flows.get(key)
                if existing is None or existing.initial != sequence:
                    if len(self.flows) >= 4:
                        self.flows.clear()
                    self.flows[key] = Flow(sequence, now)
                return None
            flow = self.flows.get(key)
            if inbound and flow is not None:
                return flow.receive(sequence, payload, now)
        return None

    def current(self):
        with self.lock:
            flows = [flow for flow in self.flows.values()
                     if not flow.disabled and self.clock() - flow.updated <= 2]
            # Ambiguous simultaneous connections never select a vehicle.
            if len(flows) == 1 and flows[0].identity:
                return flows[0].token, flows[0].identity
        return None


def exact(stream, size):
    data = b''
    while len(data) < size:
        part = stream.read(size - len(data))
        if not part:
            if not data:
                raise EOFError()
            raise ValueError('truncated pcap')
        data += part
    return data


def pcap_packets(stream):
    header = exact(stream, 24)
    magic = header[:4]
    if magic in (b'\xd4\xc3\xb2\xa1', b'\x4d\x3c\xb2\xa1'):
        order = '<'
    elif magic in (b'\xa1\xb2\xc3\xd4', b'\xa1\xb2\x3c\x4d'):
        order = '>'
    else:
        raise ValueError('unsupported pcap magic')
    major, minor, zone, accuracy, snaplen, linktype = struct.unpack(order + 'HHiiii', header[4:])
    if (major, minor, linktype) != (2, 4, 1) or not 64 <= snaplen <= 1048576:
        raise ValueError('unsupported pcap format (Ethernet required)')
    while True:
        try:
            record = exact(stream, 16)
        except EOFError:
            return
        seconds, fraction, included, original = struct.unpack(order + 'IIII', record)
        if included > snaplen or included > original:
            raise ValueError('invalid pcap packet length')
        yield exact(stream, included)


def receiver_line(stream):
    value = stream.readline(162)
    if not value.endswith(b'\n') or len(value) > 161:
        raise ValueError('invalid receiver response')
    return value[:-1].decode('ascii')


def publish(observer, configuration, key, stop):
    previous_error = None
    while not stop.is_set():
        connection = None
        stream = None
        try:
            connection = socket.create_connection((configuration['host'], int(configuration.get('port', 15119))), 3)
            connection.settimeout(4)
            stream = connection.makefile('rb')
            challenge = receiver_line(stream).split(' ')
            if len(challenge) != 2 or challenge[0] != 'QC45-AUTOCHARGE/1' or len(challenge[1]) != 64:
                raise ValueError('invalid receiver challenge')
            binascii.unhexlify(challenge[1])
            signature = hmac.new(key, ('QC45-AUTOCHARGE/1\n' + challenge[1]).encode('ascii'), hashlib.sha256).hexdigest()
            connection.sendall(('AUTH ' + signature + '\n').encode('ascii'))
            if receiver_line(stream) != 'OK':
                raise ValueError('receiver authentication failed')
            # Never transfer a remembered vehicle into a new receiver link.
            observer.reset()
            print('AutoCharge receiver connected; waiting for a new vehicle TCP session', file=sys.stderr)
            previous_error = None
            while not stop.is_set():
                current = observer.current()
                message = 'VEHICLE %s %s' % current if current else 'IDLE'
                connection.sendall((message + '\n').encode('ascii'))
                # Detect EOF promptly, including a receiver/JVM restart.
                import select
                readable, unused, unused2 = select.select([connection], [], [], 1)
                if readable:
                    raise IOError('observation receiver closed connection')
        except Exception as error:
            observer.reset()
            description = str(error)
            if description != previous_error:
                print('AutoCharge receiver unavailable: ' + description, file=sys.stderr)
                previous_error = description
            stop.wait(3)
        finally:
            if stream is not None:
                stream.close()
            if connection is not None:
                connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', help='JSON: host, port, interface, key_file')
    parser.add_argument('--check-config', action='store_true', help='validate local setup without capture or network access')
    parser.add_argument('--print-evccid', action='store_true', help='read-only enrollment; no receiver or OCPP calls')
    parser.add_argument('--pcap', help='offline check; requires --print-evccid and --local-mac')
    parser.add_argument('--local-mac', help='PLC interface MAC for offline checks')
    args = parser.parse_args()
    if args.pcap and (not args.print_evccid or not args.local_mac):
        parser.error('offline captures require --print-evccid and --local-mac; never used for live authorization')
    if not args.print_evccid and not args.config:
        parser.error('--config is required for the observation link')
    configuration = {}
    if args.config:
        with open(args.config, 'r') as stream:
            configuration = json.load(stream)
    interface = configuration.get('interface', 'qca0')
    if not interface or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.' for c in interface):
        parser.error('invalid PLC interface name')
    if args.local_mac:
        local_mac = binascii.unhexlify(args.local_mac.replace(':', ''))
    else:
        with open('/sys/class/net/' + interface + '/address', 'r') as stream:
            local_mac = binascii.unhexlify(stream.read().strip().replace(':', ''))
    if len(local_mac) != 6:
        parser.error('local PLC MAC must have six bytes')
    if args.check_config:
        if not args.config or not configuration.get('host'):
            raise ValueError('receiver host and config required')
        if not 1 <= int(configuration.get('port', 15119)) <= 65535:
            raise ValueError('invalid receiver port')
        with open(configuration['key_file'], 'r') as key_file:
            encoded = key_file.read(67).strip()
        if len(encoded) != 64:
            raise ValueError('key_file must contain 64 hex characters')
        binascii.unhexlify(encoded)
        if not os.path.isfile('/usr/sbin/tcpdump'):
            raise ValueError('tcpdump not installed')
        print('AutoCharge local configuration OK; no network or charging commands sent')
        return
    observer = Observer(local_mac)
    stop = threading.Event()
    process = None
    stream = None
    try:
        if not args.print_evccid:
            with open(configuration['key_file'], 'r') as key_file:
                encoded = key_file.read(67).strip()
            if len(encoded) != 64:
                raise ValueError('key_file must contain 64 hex characters')
            key = binascii.unhexlify(encoded)
            worker = threading.Thread(target=publish, args=(observer, configuration, key, stop))
            worker.daemon = True
            worker.start()
        if args.pcap:
            stream = open(args.pcap, 'rb')
        else:
            process = subprocess.Popen(['/usr/sbin/tcpdump', '-p', '-n', '-i', interface,
                                        '-s', '0', '-U', '-w', '-', 'ip6'], stdout=subprocess.PIPE)
            stream = process.stdout
        for packet in pcap_packets(stream):
            identity = observer.packet(packet)
            if identity and args.print_evccid:
                print('EVCCID=' + identity)
                sys.stdout.flush()
        if process is not None:
            raise RuntimeError('tcpdump stream ended; service must restart')
    finally:
        stop.set()
        if stream is not None:
            stream.close()
        if process is not None:
            process.terminate()
            process.wait()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda unused, unused2: sys.exit(0))
    try:
        main()
    except KeyboardInterrupt:
        pass
