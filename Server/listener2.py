#!/usr/bin/env python3
"""
Simple UDP Listener Server
Listens for UDP packets, logs them to a database using millisecond timestamps,
and responds back with the matched sequence number + a 500-bit ACK bitstring.
"""

import socket
import sqlite3
import time
import yaml

def load_config(path="config.yaml"):
    with open(path, "r") as f:
        return yaml.safe_load(f)

config = load_config()

DB_NAME = config["database"]
TABLE_NAME = "ack_session_server"

# In-memory store: session_id -> set of acked seq_nums
session_ack_bits = {}

def get_current_time_ms():
    """Returns the current time in milliseconds since the epoch."""
    return int(time.time() * 1000)

def build_bitstring(session_id, seq_num, length=500):
    """
    Returns a bitstring of `length` chars.
    Position i is '1' if seq i has been ACKed for this session, else '0'.
    seq_num is 1-indexed; position 0 in string = seq 1.
    """
    if session_id not in session_ack_bits:
        session_ack_bits[session_id] = set()
    session_ack_bits[session_id].add(seq_num)

    bits = []
    for i in range(1, length + 1):
        bits.append('1' if i in session_ack_bits[session_id] else '0')
    return ''.join(bits)

def setup_database():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()
    create_table_query = f"""
    CREATE TABLE IF NOT EXISTS {TABLE_NAME} (
        timestamp INTEGER,
        seq_num INTEGER,
        time_received INTEGER,
        time_sent INTEGER,
        session_id TEXT
    )
    """
    cursor.execute(create_table_query)
    conn.commit()
    print(f"Database '{DB_NAME}' and table '{TABLE_NAME}' are ready (using MS timestamps).")
    return conn

def run_udp_server(conn, host="0.0.0.0", port=50002):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, port))
    print(f"UDP server listening on {host}:{port}")

    while True:
        try:
            data, addr = sock.recvfrom(1024)
            time_received_ms = get_current_time_ms()
            msg = data.decode(errors='ignore')
            print(f"Received from {addr}: {msg}")

            session_id = None
            seq_num = None
            reply = "ACK: INVALID_FORMAT"

            try:
                parts = msg.split(',')
                if len(parts) == 2:
                    session_id = parts[0]
                    seq_num = int(parts[1])
                    bitstring = build_bitstring(session_id, seq_num)
                    reply = f"ACK {seq_num} {bitstring}"
                else:
                    print("Error: Invalid message format. Expected 'session_id,seq'.")
            except ValueError:
                print(f"Error: Could not parse seq_num as integer from '{msg}'")
            except Exception as e:
                print(f"Error parsing message: {e}")

            time_sent_ms = get_current_time_ms()

            print(f"Sending reply to {addr}: ACK {seq_num} <bitstring>")
            sock.sendto(reply.encode(), addr)

            if session_id is not None and seq_num is not None:
                try:
                    cursor = conn.cursor()
                    insert_query = f"""
                    INSERT INTO {TABLE_NAME} (timestamp, seq_num, time_received, time_sent, session_id)
                    VALUES (?, ?, ?, ?, ?)
                    """
                    db_data = (time_received_ms, seq_num, time_received_ms, time_sent_ms, session_id)
                    cursor.execute(insert_query, db_data)
                    conn.commit()
                    print(f"Logged to DB: (Session: {session_id}, Seq: {seq_num})")
                except Exception as e:
                    print(f"DATABASE ERROR: {e}")

        except Exception as e:
            print(f"An error occurred in the main loop: {e}")

if __name__ == "__main__":
    try:
        db_conn = setup_database()
        run_udp_server(db_conn, port=50002)
    except Exception as e:
        print(f"Failed to start server: {e}")
    finally:
        if 'db_conn' in locals() and db_conn:
            db_conn.close()
            print("Database connection closed.")
