#!/usr/bin/env python3
"""
Simple UDP Listener Server
Listens for UDP packets from your ping app and responds back.
"""

import socket

def run_udp_server(host="0.0.0.0", port=9999):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, port))
    print(f"UDP server listening on {host}:{port}")

    while True:
        data, addr = sock.recvfrom(1024)  # buffer size 1024 bytes
        print(f"Received from {addr}: {data.decode(errors='ignore')}")

        # Send response (like a "pong")
        reply = f"ACK: {data.decode(errors='ignore')}"
        sock.sendto(reply.encode(), addr)

if __name__ == "__main__":
    run_udp_server()
