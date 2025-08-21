#!/usr/bin/env python3
"""
REST API Server for Ping App with Data Storage and Location Support
Handles data uploads from Android ping app and stores in SQLite database
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import logging
import sqlite3
from datetime import datetime
import urllib.parse
import os
import threading

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class PingDataServer:
    def __init__(self, db_path="ping_data.db"):
        self.db_path = db_path
        self.lock = threading.Lock()
        self.init_database()

    def init_database(self):
        """Initialize SQLite database with location support"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            # Table for client/device information with location
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS clients (
                    client_id TEXT PRIMARY KEY,
                    device_id TEXT,
                    device_model TEXT,
                    android_version TEXT,
                    app_version TEXT,
                    first_seen TEXT,
                    last_seen TEXT,
                    last_location TEXT,
                    total_sessions INTEGER DEFAULT 0
                )
            ''')

            # Table for ping sessions with location data
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS ping_sessions (
                    session_id TEXT PRIMARY KEY,
                    client_id TEXT,
                    host TEXT,
                    protocol TEXT,
                    start_time TEXT,
                    end_time TEXT,
                    duration_seconds INTEGER,
                    packets_sent INTEGER,
                    packets_received INTEGER,
                    packet_loss_percent REAL,
                    avg_rtt_ms REAL,
                    min_rtt_ms REAL,
                    max_rtt_ms REAL,
                    total_bytes BIGINT,
                    avg_bandwidth_bps REAL,
                    start_location TEXT,
                    end_location TEXT,
                    settings_json TEXT,
                    FOREIGN KEY (client_id) REFERENCES clients (client_id)
                )
            ''')

            # Table for individual ping results with location
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS ping_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    timestamp TEXT,
                    sequence_number INTEGER,
                    success BOOLEAN,
                    rtt_ms REAL,
                    location TEXT,
                    error_message TEXT,
                    FOREIGN KEY (session_id) REFERENCES ping_sessions (session_id)
                )
            ''')

            # Table for heartbeat logs with location
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS heartbeats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_id TEXT,
                    device_id TEXT,
                    app_status TEXT,
                    location TEXT,
                    timestamp TEXT,
                    FOREIGN KEY (client_id) REFERENCES clients (client_id)
                )
            ''')

            # Check if location columns exist in existing tables (for database upgrades)
            cursor.execute("PRAGMA table_info(clients)")
            columns = [column[1] for column in cursor.fetchall()]
            if 'last_location' not in columns:
                cursor.execute('ALTER TABLE clients ADD COLUMN last_location TEXT DEFAULT "N/A"')
                logger.info("Added last_location column to clients table")

            cursor.execute("PRAGMA table_info(ping_sessions)")
            columns = [column[1] for column in cursor.fetchall()]
            if 'start_location' not in columns:
                cursor.execute('ALTER TABLE ping_sessions ADD COLUMN start_location TEXT DEFAULT "N/A"')
                cursor.execute('ALTER TABLE ping_sessions ADD COLUMN end_location TEXT DEFAULT "N/A"')
                logger.info("Added location columns to ping_sessions table")

            cursor.execute("PRAGMA table_info(ping_results)")
            columns = [column[1] for column in cursor.fetchall()]
            if 'location' not in columns:
                cursor.execute('ALTER TABLE ping_results ADD COLUMN location TEXT DEFAULT "N/A"')
                logger.info("Added location column to ping_results table")

            conn.commit()
            conn.close()
            logger.info("Database initialized successfully with location support")

        except Exception as e:
            logger.error(f"Database initialization error: {e}")

    def register_or_update_client(self, device_info):
        """Register new client or update existing one with location"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                client_id = f"{device_info['device_id']}_{device_info.get('device_model', '').replace(' ', '_')}"
                current_time = datetime.now().isoformat()
                location = device_info.get('location', 'N/A')

                # Check if client exists
                cursor.execute('SELECT client_id, total_sessions FROM clients WHERE client_id = ?', (client_id,))
                result = cursor.fetchone()

                if result:
                    # Update existing client with location
                    total_sessions = result[1]
                    cursor.execute('''
                        UPDATE clients SET
                        last_seen = ?,
                        device_model = ?,
                        android_version = ?,
                        app_version = ?,
                        last_location = ?
                        WHERE client_id = ?
                    ''', (current_time, device_info.get('device_model', ''),
                         device_info.get('android_version', ''),
                         device_info.get('app_version', ''),
                         location, client_id))
                else:
                    # Insert new client with location
                    total_sessions = 0
                    cursor.execute('''
                        INSERT INTO clients
                        (client_id, device_id, device_model, android_version, app_version,
                         first_seen, last_seen, last_location, total_sessions)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ''', (client_id, device_info['device_id'], device_info.get('device_model', ''),
                         device_info.get('android_version', ''), device_info.get('app_version', ''),
                         current_time, current_time, location, total_sessions))

                conn.commit()
                conn.close()
                return client_id

            except Exception as e:
                logger.error(f"Error registering client: {e}")
                return None

    def store_heartbeat(self, heartbeat_data):
        """Store heartbeat with location data"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                cursor.execute('''
                    INSERT INTO heartbeats
                    (client_id, device_id, app_status, location, timestamp)
                    VALUES (?, ?, ?, ?, ?)
                ''', (
                    heartbeat_data.get('client_id', ''),
                    heartbeat_data.get('device_id', ''),
                    heartbeat_data.get('app_status', 'unknown'),
                    heartbeat_data.get('location', 'N/A'),
                    datetime.now().isoformat()
                ))

                # Update client's last location
                if heartbeat_data.get('client_id'):
                    cursor.execute('''
                        UPDATE clients SET
                        last_seen = ?,
                        last_location = ?
                        WHERE client_id = ?
                    ''', (datetime.now().isoformat(),
                         heartbeat_data.get('location', 'N/A'),
                         heartbeat_data.get('client_id')))

                conn.commit()
                conn.close()
                return True

            except Exception as e:
                logger.error(f"Error storing heartbeat: {e}")
                return False

    def store_ping_session(self, session_data):
        """Store complete ping session data with location information"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                # Insert session data with location
                cursor.execute('''
                    INSERT INTO ping_sessions
                    (session_id, client_id, host, protocol, start_time, end_time,
                     duration_seconds, packets_sent, packets_received, packet_loss_percent,
                     avg_rtt_ms, min_rtt_ms, max_rtt_ms, total_bytes, avg_bandwidth_bps,
                     start_location, end_location, settings_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', (
                    session_data['session_id'], session_data['client_id'], session_data['host'],
                    session_data['protocol'], session_data['start_time'], session_data['end_time'],
                    session_data['duration_seconds'], session_data['packets_sent'],
                    session_data['packets_received'], session_data['packet_loss_percent'],
                    session_data.get('avg_rtt_ms', 0), session_data.get('min_rtt_ms', 0),
                    session_data.get('max_rtt_ms', 0), session_data.get('total_bytes', 0),
                    session_data.get('avg_bandwidth_bps', 0), session_data.get('start_location', 'N/A'),
                    session_data.get('end_location', 'N/A'), json.dumps(session_data.get('settings', {}))
                ))

                # Update client session count and last location
                cursor.execute('''
                    UPDATE clients SET
                    total_sessions = total_sessions + 1,
                    last_location = ?
                    WHERE client_id = ?
                ''', (session_data.get('end_location', 'N/A'), session_data['client_id']))

                # Store individual ping results with location if provided
                if 'ping_results' in session_data:
                    for result in session_data['ping_results']:
                        cursor.execute('''
                            INSERT INTO ping_results
                            (session_id, timestamp, sequence_number, success, rtt_ms, location, error_message)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        ''', (
                            session_data['session_id'], result.get('timestamp', ''),
                            result.get('sequence', 0), result.get('success', False),
                            result.get('rtt_ms', 0), result.get('location', 'N/A'),
                            result.get('error_message', '')
                        ))

                conn.commit()
                conn.close()
                return True

            except Exception as e:
                logger.error(f"Error storing session data: {e}")
                return False

    def get_client_stats(self, client_id=None):
        """Get statistics for specific client or all clients"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            if client_id:
                cursor.execute('''
                    SELECT c.*, COUNT(s.session_id) as actual_sessions
                    FROM clients c
                    LEFT JOIN ping_sessions s ON c.client_id = s.client_id
                    WHERE c.client_id = ?
                    GROUP BY c.client_id
                ''', (client_id,))
                result = cursor.fetchone()
                if result:
                    columns = [desc[0] for desc in cursor.description]
                    return dict(zip(columns, result))
            else:
                cursor.execute('''
                    SELECT c.*, COUNT(s.session_id) as actual_sessions
                    FROM clients c
                    LEFT JOIN ping_sessions s ON c.client_id = s.client_id
                    GROUP BY c.client_id
                ''')
                results = cursor.fetchall()
                columns = [desc[0] for desc in cursor.description]
                return [dict(zip(columns, row)) for row in results]

            conn.close()
            return None

        except Exception as e:
            logger.error(f"Error getting client stats: {e}")
            return None

    def get_location_stats(self):
        """Get location-based statistics"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            # Get session counts by location
            cursor.execute('''
                SELECT start_location, COUNT(*) as session_count
                FROM ping_sessions
                WHERE start_location != 'N/A'
                GROUP BY start_location
                ORDER BY session_count DESC
            ''')
            location_sessions = cursor.fetchall()

            # Get unique locations from clients
            cursor.execute('''
                SELECT DISTINCT last_location, COUNT(*) as client_count
                FROM clients
                WHERE last_location != 'N/A'
                GROUP BY last_location
                ORDER BY client_count DESC
            ''')
            location_clients = cursor.fetchall()

            conn.close()

            return {
                'sessions_by_location': [{'location': loc, 'count': count} for loc, count in location_sessions],
                'clients_by_location': [{'location': loc, 'count': count} for loc, count in location_clients]
            }

        except Exception as e:
            logger.error(f"Error getting location stats: {e}")
            return None

# Global database instance
db = PingDataServer()

class PingRestApiHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        """Handle preflight CORS requests"""
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.end_headers()

    def do_GET(self):
        """Handle GET requests"""
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path

        if path == '/' or path == '/api':
            self.handle_api_info()
        elif path == '/api/status':
            self.handle_status()
        elif path == '/api/ping':
            self.handle_ping()
        elif path == '/api/clients':
            self.handle_get_clients()
        elif path == '/api/locations':
            self.handle_get_locations()
        elif path.startswith('/api/clients/'):
            client_id = path.split('/')[-1]
            self.handle_get_client_data(client_id)
        else:
            self.send_json_error(404, "Endpoint not found")

    def do_POST(self):
        """Handle POST requests"""
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path

        if path == '/api/connect':
            self.handle_connect()
        elif path == '/api/heartbeat':
            self.handle_heartbeat()
        elif path == '/api/upload-session':
            self.handle_upload_session()
        else:
            self.send_json_error(404, "Endpoint not found")

    def send_json_response(self, status_code, data):
        """Send JSON response with proper headers"""
        response_json = json.dumps(data, indent=2)

        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(response_json.encode())))
        self.end_headers()
        self.wfile.write(response_json.encode())

    def send_json_error(self, status_code, message):
        """Send JSON error response"""
        error_data = {
            "error": True,
            "status_code": status_code,
            "message": message,
            "timestamp": datetime.now().isoformat()
        }
        self.send_json_response(status_code, error_data)

    def parse_json_body(self):
        """Parse JSON from request body"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            if content_length == 0:
                return {}

            body = self.rfile.read(content_length)
            return json.loads(body.decode('utf-8'))
        except json.JSONDecodeError:
            raise ValueError("Invalid JSON in request body")
        except Exception as e:
            raise ValueError(f"Error reading request body: {str(e)}")

    def handle_api_info(self):
        """API information endpoint"""
        api_info = {
            "name": "Ping App REST API with Location Support",
            "version": "2.1.0",
            "description": "REST API for Android Ping App with SQLite data storage and location tracking",
            "timestamp": datetime.now().isoformat(),
            "endpoints": {
                "GET /api": "API information",
                "GET /api/status": "Server status",
                "GET /api/ping": "Simple ping test",
                "GET /api/clients": "List all clients",
                "GET /api/clients/{id}": "Get specific client data",
                "GET /api/locations": "Get location statistics",
                "POST /api/connect": "Connect with device info and location",
                "POST /api/heartbeat": "Send heartbeat signal with location",
                "POST /api/upload-session": "Upload ping session data with location"
            },
            "new_features": [
                "Location tracking for clients and ping sessions",
                "Location-based statistics",
                "Enhanced heartbeat with location data"
            ]
        }

        self.send_json_response(200, api_info)
        logger.info(f"API info requested from {self.client_address[0]}")

    def handle_status(self):
        """Server status endpoint"""
        status = {
            "status": "online",
            "message": "Server is running with data storage and location support",
            "timestamp": datetime.now().isoformat(),
            "database": "SQLite",
            "database_file": db.db_path,
            "client_ip": self.client_address[0],
            "features": ["location_tracking", "data_storage", "heartbeat_monitoring"]
        }

        self.send_json_response(200, status)
        logger.info(f"Status check from {self.client_address[0]}")

    def handle_ping(self):
        """Simple ping test endpoint"""
        ping_response = {
            "ping": "pong",
            "timestamp": datetime.now().isoformat(),
            "client_ip": self.client_address[0]
        }

        self.send_json_response(200, ping_response)
        logger.info(f"Ping test from {self.client_address[0]}")

    def handle_connect(self):
        """Handle device connection with registration and location"""
        try:
            request_data = self.parse_json_body()

            # Register or update client with location
            client_id = db.register_or_update_client(request_data)

            if client_id:
                location = request_data.get('location', 'N/A')
                logger.info(f"Client registered/updated: {client_id} at location: {location}")

                response = {
                    "status": "connected",
                    "message": "Device registered successfully with location",
                    "client_id": client_id,
                    "location_recorded": location,
                    "timestamp": datetime.now().isoformat(),
                    "server_time": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(500, "Failed to register client")

        except ValueError as e:
            logger.error(f"Connection error: {e}")
            self.send_json_error(400, str(e))
        except Exception as e:
            logger.error(f"Unexpected connection error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_heartbeat(self):
        """Handle heartbeat signals with location"""
        try:
            request_data = self.parse_json_body()
            device_id = request_data.get('device_id', 'unknown')
            location = request_data.get('location', 'N/A')

            # Store heartbeat data
            db.store_heartbeat(request_data)

            logger.info(f"Heartbeat from device: {device_id} at location: {location}")

            response = {
                "heartbeat": "acknowledged",
                "server_status": "online",
                "location_recorded": location,
                "timestamp": datetime.now().isoformat(),
                "next_heartbeat_in_seconds": 3600
            }

            self.send_json_response(200, response)

        except Exception as e:
            logger.error(f"Heartbeat error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_upload_session(self):
        """Handle ping session data upload with location"""
        try:
            session_data = self.parse_json_body()

            # Validate required fields
            required_fields = ['session_id', 'client_id', 'host', 'protocol',
                             'start_time', 'end_time', 'packets_sent', 'packets_received']

            for field in required_fields:
                if field not in session_data:
                    self.send_json_error(400, f"Missing required field: {field}")
                    return

            # Store session data with location
            success = db.store_ping_session(session_data)

            if success:
                start_loc = session_data.get('start_location', 'N/A')
                end_loc = session_data.get('end_location', 'N/A')
                logger.info(f"Session data stored: {session_data['session_id']} from {session_data['client_id']} (Location: {start_loc} -> {end_loc})")

                response = {
                    "status": "success",
                    "message": "Session data uploaded successfully with location",
                    "session_id": session_data['session_id'],
                    "start_location": start_loc,
                    "end_location": end_loc,
                    "timestamp": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(500, "Failed to store session data")

        except ValueError as e:
            logger.error(f"Upload error: {e}")
            self.send_json_error(400, str(e))
        except Exception as e:
            logger.error(f"Unexpected upload error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_get_clients(self):
        """Get all clients data"""
        try:
            clients = db.get_client_stats()

            response = {
                "status": "success",
                "clients": clients,
                "total_clients": len(clients) if clients else 0,
                "timestamp": datetime.now().isoformat()
            }

            self.send_json_response(200, response)
            logger.info(f"Clients data requested from {self.client_address[0]}")

        except Exception as e:
            logger.error(f"Error getting clients: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_get_client_data(self, client_id):
        """Get specific client data"""
        try:
            client_data = db.get_client_stats(client_id)

            if client_data:
                response = {
                    "status": "success",
                    "client": client_data,
                    "timestamp": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(404, f"Client {client_id} not found")

        except Exception as e:
            logger.error(f"Error getting client data: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_get_locations(self):
        """Get location-based statistics"""
        try:
            location_stats = db.get_location_stats()

            if location_stats:
                response = {
                    "status": "success",
                    "location_statistics": location_stats,
                    "timestamp": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(500, "Failed to retrieve location statistics")

        except Exception as e:
            logger.error(f"Error getting location stats: {e}")
            self.send_json_error(500, "Internal server error")

    def log_message(self, format, *args):
        """Override to use our logger"""
        logger.info(f"{self.client_address[0]} - {format % args}")

def run_server(port=8080):
    """Start the REST API server"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, PingRestApiHandler)

    logger.info(f"Starting Ping REST API Server with Location Support on port {port}")
    logger.info("Available REST endpoints:")
    logger.info("  GET  /api                - API information")
    logger.info("  GET  /api/status         - Server status")
    logger.info("  GET  /api/ping           - Simple ping test")
    logger.info("  GET  /api/clients        - List all clients")
    logger.info("  GET  /api/clients/{id}   - Get client data")
    logger.info("  GET  /api/locations      - Get location statistics")
    logger.info("  POST /api/connect        - Connect device with location")
    logger.info("  POST /api/heartbeat      - Send heartbeat with location")
    logger.info("  POST /api/upload-session - Upload session data with location")
    logger.info(f"Database: {db.db_path}")
    logger.info("New features: Location tracking, Enhanced statistics")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        logger.info("Server shutting down...")
        httpd.shutdown()

if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='REST API Server for Ping App with Location Support')
    parser.add_argument('--port', type=int, default=8080, help='Server port (default: 8080)')
    parser.add_argument('--db', type=str, default='ping_data.db', help='Database file path')
    args = parser.parse_args()

    db = PingDataServer(args.db)
    run_server(args.port)