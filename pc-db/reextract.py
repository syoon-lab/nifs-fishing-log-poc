"""Offline extraction evaluation from saved transcripts; never writes survey DBs.

Inputs contain only id, phase and transcript(s), not expected answers. Use the ML
Python from models.json. Outputs preserve raw claims and each validation attempt.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time

from inference_worker import Worker, strict_json
from transcript_selection import select_transcript


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    inputs = strict_json(args.inputs.read_text())
    if not isinstance(inputs, list):
        raise ValueError('Inputs must be an array without evaluation labels')
    allowed = {'id', 'phase', 'asr_text', 'dialect_text', 'text', 'question_context', 'dialect_error'}
    if any(not isinstance(row, dict) or set(row) - allowed for row in inputs):
        raise ValueError('Unsupported input keys; expected answers must not be provided')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    config = strict_json(args.config.read_text())
    prompt = Path(config['extraction_prompt']).read_text()
    frozen_prompt = args.output.with_suffix('.prompt.txt')
    frozen_config = args.output.with_suffix('.config.json')
    if any(path.exists() for path in (args.output, frozen_prompt, frozen_config)):
        raise FileExistsError('Use a new output path; existing evaluation evidence is immutable')
    with frozen_prompt.open('x') as out:
        out.write(prompt)
    config['extraction_prompt'] = str(frozen_prompt.resolve())
    with frozen_config.open('x') as out:
        out.write(json.dumps(config, ensure_ascii=False, indent=2))
    worker = Worker(frozen_config)
    with args.output.open('x') as out:
        for row in inputs:
            started = time.monotonic()
            result = {'asr_text': row.get('asr_text', row.get('text', '')),
                      'dialect_text': row.get('dialect_text'), 'extraction': None}
            if row.get('dialect_error'):
                result['dialect_error'] = row['dialect_error']
            if 'text' in row and 'asr_text' not in row and 'dialect_text' not in row:
                result['input_kind'] = 'text_only_evaluation'
                select_transcript(result, False)
            try:
                worker.extract_result(result, row.get('phase'), question_context=row.get('question_context'))
            except Exception as error:
                result['error'] = str(error)
            item = {'id': row['id'], 'result': result,
                    'elapsed_seconds': round(time.monotonic() - started, 3),
                    'prompt_sha256': hashlib.sha256(prompt.encode()).hexdigest()}
            out.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + '\n')
            out.flush()
            print(json.dumps({'id': row['id'], 'error': result.get('error'),
                              'elapsed_seconds': item['elapsed_seconds']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
