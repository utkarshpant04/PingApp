#!/usr/bin/env python3
"""
Simple UDP Listener Server
Listens for UDP packets, logs them to a database using millisecond timestamps,
and responds back with the matched sequence number.
"""

import socket
import sqlite3
import time  # Use time module for epoch timestamps

# --- Database Configuration ---
DB_NAME = "ping_data2.db"
TABLE_NAME = "ack_session_server"
# ------------------------------

def get_current_time_ms():
    """Returns the current time in milliseconds since the epoch."""
    return int(time.time() * 1000)

def setup_database():
    """
    Connects to the SQLite DB and creates the ack_session_server table
    if it doesn't already exist.
    """
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()

    # --- Schema Changed Here ---
    # Changed timestamp fields from TEXT to INTEGER to store milliseconds
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
    """
    Runs the main UDP server loop.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, port))
    print(f"UDP server listening on {host}:{port}")

    while True:
        try:
            # 1. Receive data
            data, addr = sock.recvfrom(1024) # buffer size 1024 bytes

            # --- Timestamp Changed Here ---
            time_received_ms = get_current_time_ms()
            # -----------------------------

            msg = data.decode(errors='ignore')
            print(f"Received from {addr}: {msg}")

            session_id = None
            seq_num = None
            reply = "ACK: INVALID_FORMAT" # Default reply

            # 2. Parse the new "session_id,seq" format
            try:
                parts = msg.split(',')
                if len(parts) == 2:
                    session_id = parts[0]
                    seq_num = int(parts[1]) # Can raise ValueError
                    reply = f"ACK {seq_num}" # Prepare a valid reply
                else:
                    print("Error: Invalid message format. Expected 'session_id,seq'.")
            except ValueError:
                print(f"Error: Could not parse seq_num as integer from '{msg}'")
            except Exception as e:
                print(f"Error parsing message: {e}")

            # --- Timestamp Changed Here ---
            # Capture 'time_sent' right before sending
            time_sent_ms = get_current_time_ms()
            # -----------------------------

            # 3. Send reply
            print(f"Sending reply to {addr}: {reply}")
            sock.sendto(reply.encode(), addr)

            # 4. Log to database (if parsing was successful)
            if session_id is not None and seq_num is not None:
                try:
                    cursor = conn.cursor()
                    insert_query = f"""
                    INSERT INTO {TABLE_NAME} (timestamp, seq_num, time_received, time_sent, session_id)
                    VALUES (?, ?, ?, ?, ?)
                    """

                    # --- DB Data Changed Here ---
                    # We use time_received_ms for both 'timestamp' and 'time_received' columns
                    db_data = (time_received_ms, seq_num, time_received_ms, time_sent_ms, session_id)
                    # -----------------------------

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
        # Run the server on port 50002 to match the app
        run_udp_server(db_conn, port=50002)
    except Exception as e:
        print(f"Failed to start server: {e}")
    finally:
        if 'db_conn' in locals() and db_conn:
            db_conn.close()
            print("Database connection closed.")