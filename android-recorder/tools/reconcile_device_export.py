#!/usr/bin/env python3
"""Compare an idle device's private SQLite/audio snapshot with its UI-exported ZIP."""
import csv
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import sys
import tarfile
import tempfile
import zipfile

LEGACY_TABLES = ('sessions', 'recordings', 'locations', 'events')
from verify_export import verify as verify_archive


def verify(snapshot_path, export_path):
    verify_archive(export_path)
    with tarfile.open(snapshot_path) as source, zipfile.ZipFile(export_path) as exported:
        members = [m for m in source.getmembers() if m.isfile()]
        names = [m.name.removeprefix('./') for m in members]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate source snapshot paths')
        source_files = dict(zip(names, members))
        db_bytes = source.extractfile(source_files['databases/field-records.db']).read()
        data = json.loads(exported.read('records.json'))
        TABLES = LEGACY_TABLES if data['schema_version'] == 1 else ('sessions','operations','recordings','locations','events')
        if data['schema_version'] >= 3:
            TABLES += ('transcriptions','transcript_reviews','diary_entries')
        if data['schema_version'] >= 4:
            TABLES += ('structured_logs','catch_items')
        with tempfile.TemporaryDirectory() as temp:
            db_path = Path(temp) / 'source.db'
            db_path.write_bytes(db_bytes)
            db = sqlite3.connect(str(db_path))
            db.row_factory = sqlite3.Row
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
            actual_tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'")}
            assert actual_tables == set(TABLES), actual_tables
            summary = {}
            originals = {}
            for table in TABLES:
                rows = [dict(r) for r in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')]
                originals[table] = rows
                columns = [r[1] for r in db.execute('PRAGMA table_info(' + table + ')')]
                assert all(set(r) == set(columns) for r in data[table]), table + ': JSON columns'
                assert rows == data[table], table + ': original DB/JSON rows differ'
                reader = csv.DictReader(io.StringIO(exported.read(table + '.csv').decode('utf-8-sig'), newline=''))
                csv_rows = list(reader)
                assert reader.fieldnames == columns, table + ': CSV columns'
                assert len(csv_rows) == len(rows), table + ': CSV row count'
                ids = [r['id'] for r in rows]
                assert len(ids) == len(set(ids)), table + ': duplicate ID'
                summary[table] = {'source_rows': len(rows), 'export_rows': len(data[table]), 'excluded_rows': 0, 'columns': columns}
            assert not any(r['status'] == 'ACTIVE' for r in originals['sessions'])
            assert not any(r['status'] in ('PREPARING', 'RECORDING') for r in originals['recordings'])
            audio = {}
            for row in originals['recordings']:
                original_name = 'files/records/' + row['path']
                raw = source.extractfile(source_files[original_name]).read()
                target = exported.read(row['path'])
                assert raw == target, row['path'] + ': source audio differs'
                audio[row['id']] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'status': row['status'], 'started_ms': row['started_ms'], 'ended_ms': row['ended_ms']}
            actual_audio = {n for n in source_files if n.startswith('files/records/audio/')}
            expected_audio = {'files/records/' + r['path'] for r in originals['recordings']}
            assert actual_audio == expected_audio, 'Missing or orphan source audio'
            location_times = [r['measured_ms'] for r in originals['locations']]
            providers = {}
            for r in originals['locations']:
                providers[r['provider']] = providers.get(r['provider'], 0) + 1
            db.close()
            return {'verified_against_device_original': True, 'source_db_sha256': hashlib.sha256(db_bytes).hexdigest(), 'source_tar_sha256': hashlib.sha256(Path(snapshot_path).read_bytes()).hexdigest(), 'export_zip_sha256': hashlib.sha256(Path(export_path).read_bytes()).hexdigest(), 'tables': summary, 'audio': audio, 'location_providers': providers, 'location_period_ms': [min(location_times), max(location_times)] if location_times else None, 'mock_location_count': sum(r['is_mock'] != 0 for r in originals['locations'])}


if __name__ == '__main__':
    result = verify(sys.argv[1], sys.argv[2])
    Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k: result[k] for k in ('verified_against_device_original', 'tables', 'location_providers', 'mock_location_count')}, ensure_ascii=False, indent=2))
