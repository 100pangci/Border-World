#!/usr/bin/env python3
"""极简 RCON 客户端：python3 tools/rcon.py <host> <port> <password> <command...>"""
import socket
import struct
import sys


def pack(request_id: int, packet_type: int, payload: str) -> bytes:
    data = struct.pack('<ii', request_id, packet_type) + payload.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data


def read_packet(sock: socket.socket):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return None, None, None
    (length,) = struct.unpack('<i', raw_len)
    body = b''
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            break
        body += chunk
    req_id, pkt_type = struct.unpack('<ii', body[:8])
    payload = body[8:-2].decode('utf-8', errors='replace')
    return req_id, pkt_type, payload


def run(host: str, port: int, password: str, command: str) -> str:
    with socket.create_connection((host, port), timeout=15) as sock:
        sock.settimeout(30)
        sock.sendall(pack(1, 3, password))
        req_id, _, _ = read_packet(sock)
        if req_id == -1:
            raise RuntimeError('RCON 认证失败')
        sock.sendall(pack(2, 2, command))
        out = []
        while True:
            _, pkt_type, payload = read_packet(sock)
            if pkt_type is None:
                break
            out.append(payload)
            if pkt_type == 2 and not payload.endswith('\x00'):
                # command response terminates when a packet arrives with the same id
                pass
            # 简单策略：读到第一个非空响应即结束（命令响应一般单包）
            if payload:
                break
        return '\n'.join(out)


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    command = ' '.join(sys.argv[4:])
    print(run(host, port, password, command))


if __name__ == '__main__':
    main()
