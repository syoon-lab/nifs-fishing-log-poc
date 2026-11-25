#!/usr/bin/env python3
"""Grade saved extraction output without invoking a model or providing labels to it.

References are evaluation-only files outside the application data directory. Use
fresh text cases only after freezing implementation; historical holdout clips are
development regression material, not independent field accuracy evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


NUMERIC_FIELDS = {'gear_quantity', 'haul_quantity', 'redeploy_quantity', 'workers',
                  'quantity', 'weight_kg'}
DEFAULTS = {'record_class': 'UNCLASSIFIED', 'notes_state': 'NOT_MENTIONED',
            'outcome': 'UNCONFIRMED', 'disposition': 'UNCONFIRMED', 'approximate': False}
ABSENT = object()


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    def constant(value):
        raise ValueError('Non-finite JSON number: ' + value)
    value = json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    def check(item):
        if isinstance(item, float) and not math.isfinite(item):
            raise ValueError('Non-finite JSON number')
        if isinstance(item, dict):
            for child in item.values():
                check(child)
        elif isinstance(item, list):
            for child in item:
                check(child)
    check(value)
    return value


def load_cases(reference, dataset='fresh'):
    document = strict_json(Path(reference).read_text(encoding='utf-8'))
    if dataset == 'fresh':
        section = document['fresh_text_evaluation']
        raw_cases = section['cases']
        description = section.get('independence_limit', '')
    elif dataset == 'legacy':
        section = document['existing_regression']
        raw_cases = section['cases']
        description = section.get('reason', '')
    else:
        raise ValueError('Unknown evaluation dataset: ' + dataset)
    cases = []
    seen = set()
    for item in raw_cases:
        case_id = item.get('id', item.get('file'))
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError('Missing or duplicated reference identifier')
        seen.add(case_id)
        expected = item.get('expected_subset', item.get('expected'))
        text = item.get('text', item.get('speech'))
        if not isinstance(expected, dict) or not isinstance(text, str):
            raise ValueError('Reference must contain input text and expected fields')
        cases.append({'id': case_id, 'phase': item.get('phase'), 'text': text,
                      'expected': expected,
                      'required_uncertainty': item.get('required_uncertainty')})
    return cases, description


def inference_inputs(cases):
    """Allowlist projection; no expectations, labels, references, or gold quotes."""
    return [{'id': item['id'], 'phase': item['phase'], 'text': item['text']} for item in cases]


def load_outputs(path):
    text = Path(path).read_text(encoding='utf-8')
    try:
        value = strict_json(text)
    except json.JSONDecodeError:
        value = [strict_json(line) for line in text.splitlines() if line.strip()]
    if isinstance(value, dict):
        for key in ('runs', 'outputs', 'results', 'cases', 'items'):
            if isinstance(value.get(key), list):
                value = value[key]
                break
        else:
            value = [value]
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ValueError('Outputs must be JSON objects, a JSON array, or JSONL objects')
    indexed = {}
    for item in value:
        case_id = item.get('case_id', item.get('id', item.get('recording_id', item.get('file'))))
        if not isinstance(case_id, str) or not case_id:
            raise ValueError('Missing output case identifier')
        if case_id in indexed:
            raise ValueError('Multiple output records for case: ' + case_id)
        indexed[case_id] = item
    return indexed


def numeric(value):
    return type(value) in (int, float) and math.isfinite(value)


def equal(expected, actual):
    if actual is ABSENT:
        return False
    if numeric(expected):
        return numeric(actual) and math.isclose(expected, actual, rel_tol=1e-9, abs_tol=1e-9)
    return type(expected) is type(actual) and expected == actual


def leaf_fields(value, prefix=''):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from leaf_fields(child, f'{prefix}.{key}' if prefix else key)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from leaf_fields(child, f'{prefix}[{i}]')
    else:
        yield prefix, value


def useful_count(fields):
    return sum(value is not None and value != '' and
               not (path.rsplit('.', 1)[-1] in DEFAULTS and
                    value == DEFAULTS[path.rsplit('.', 1)[-1]])
               for path, value in leaf_fields(fields))


def compare_fields(expected, actual):
    """Subset field checks, with catch multiplicity retained and species aligned."""
    checks = []
    def add(path, wanted, got, actual_path=None):
        checks.append({'field': path, 'actual_field': (actual_path or path) if got is not ABSENT else None,
                       'expected': wanted, 'actual': None if got is ABSENT else got,
                       'missing': got is ABSENT, 'passed': equal(wanted, got)})
    for key, wanted in expected.items():
        if key != 'catches':
            add(key, wanted, actual.get(key, ABSENT))
            continue
        actual_catches = actual.get('catches', ABSENT)
        if not isinstance(wanted, list):
            raise ValueError('Reference catches must be an array')
        if not isinstance(actual_catches, list):
            add('catches.length', len(wanted), ABSENT)
            actual_catches = []
        else:
            add('catches.length', len(wanted), len(actual_catches))
        unused = set(range(len(actual_catches)))
        for i, reference_row in enumerate(wanted):
            if not isinstance(reference_row, dict):
                raise ValueError('Reference catch row must be an object')
            # Row order is not a semantic failure; repeated rows still consume
            # distinct positions, so merging/dropping duplicates cannot pass.
            candidates = [j for j in unused if isinstance(actual_catches[j], dict)]
            same_species = [j for j in candidates if
                            actual_catches[j].get('species') == reference_row.get('species')]
            # A missing named species is an omission. Never consume another
            # named fish's row and report its valid amount as an invented value.
            named_species = isinstance(reference_row.get('species'), str) and reference_row['species'].strip()
            choices = same_species if named_species else (same_species or candidates)
            chosen = min(choices, key=lambda j: (
                -sum(equal(v, actual_catches[j].get(k, ABSENT)) for k, v in reference_row.items()), j)) if choices else None
            predicted = actual_catches[chosen] if chosen is not None else {}
            if chosen is not None:
                unused.remove(chosen)
            for field, wanted_value in reference_row.items():
                add(f'catches[{i}].{field}', wanted_value, predicted.get(field, ABSENT),
                    f'catches[{chosen}].{field}' if chosen is not None else None)
        for j in sorted(unused):
            row = actual_catches[j]
            add(f'catches.extra[{j}]', None, row)
            if isinstance(row, dict):
                for key in NUMERIC_FIELDS & row.keys():
                    if numeric(row[key]):
                        add(f'catches.extra[{j}].{key}', None, row[key], f'catches[{j}].{key}')
    return checks


def evaluate(cases, outputs):
    reports = []
    for case in cases:
        output = outputs.get(case['id'])
        if output is None:
            reports.append({'id': case['id'], 'missing_output': True, 'passed': False,
                            'accepted_output': False, 'checks': compare_fields(case['expected'], {}),
                            'useful_field_count': 0, 'unscored_numeric_claims': [],
                            'uncertainties': [], 'error': 'Missing output'})
            continue
        result = output.get('result', output)
        if not isinstance(result, dict):
            result = {}
        extraction = result.get('extraction')
        if not isinstance(extraction, dict):
            extraction = {}
        fields = extraction.get('fields')
        if not isinstance(fields, dict):
            fields = {}
        error = output.get('error') or result.get('error')
        terminal_status = output.get('status', result.get('status'))
        accepted = (bool(extraction) and extraction.get('status') == 'DRAFT' and
                    not error and terminal_status not in ('FAILED', 'INTERRUPTED', 'RUNNING', 'QUEUED'))
        attempts = result.get('extraction_attempts', [])
        if isinstance(attempts, list) and attempts and isinstance(attempts[-1], dict):
            if attempts[-1].get('validation_error'):
                accepted = False
                error = error or attempts[-1]['validation_error']
        checks = compare_fields(case['expected'], fields)
        uncertainties = extraction.get('uncertainties', [])
        uncertainty_ok = not case.get('required_uncertainty') or (
            isinstance(uncertainties, list) and any(isinstance(v, str) and v.strip() for v in uncertainties))
        graded = {item['actual_field'] for item in checks}
        unscored = [{'field': path, 'value': value} for path, value in leaf_fields(fields)
                    if path.rsplit('.', 1)[-1] in NUMERIC_FIELDS and numeric(value) and path not in graded]
        reports.append({'id': case['id'], 'missing_output': False, 'accepted_output': accepted,
                        'passed': accepted and all(item['passed'] for item in checks) and uncertainty_ok,
                        'checks': checks, 'useful_field_count': useful_count(fields),
                        'unscored_numeric_claims': unscored, 'uncertainties': uncertainties,
                        'uncertainty_present_when_required': uncertainty_ok, 'error': error})
    all_checks = [item for case in reports for item in case['checks']]
    numeric_checks = [item for item in all_checks if item['field'].rsplit('.', 1)[-1] in NUMERIC_FIELDS]
    false_numbers = [item for item in numeric_checks if numeric(item['actual']) and not item['passed']]
    abstentions = [item for item in numeric_checks if item['expected'] is None]
    positive = [item for item in all_checks if item['expected'] is not None and
                not item['field'].endswith('.length') and
                not (item['field'].rsplit('.', 1)[-1] in DEFAULTS and
                     item['expected'] == DEFAULTS[item['field'].rsplit('.', 1)[-1]])]
    metric = {
        'cases': len(reports), 'cases_passed': sum(r['passed'] for r in reports),
        'missing_outputs': sum(r['missing_output'] for r in reports),
        'accepted_outputs': sum(r['accepted_output'] for r in reports),
        'field_checks': len(all_checks), 'field_checks_passed': sum(r['passed'] for r in all_checks),
        'positive_expected_field_checks': len(positive),
        'positive_expected_fields_recovered': sum(r['passed'] for r in positive),
        'false_numeric_claims_on_labeled_fields': len(false_numbers),
        'accepted_false_numeric_claims': sum(
            numeric(check['actual']) and not check['passed']
            for case in reports if case['accepted_output'] for check in case['checks']
            if check['field'].rsplit('.', 1)[-1] in NUMERIC_FIELDS),
        'numeric_abstention_checks': len(abstentions),
        'numeric_abstentions_correct': sum(r['passed'] for r in abstentions),
        'useful_populated_fields': sum(r['useful_field_count'] for r in reports),
        'accepted_useful_populated_fields': sum(r['useful_field_count'] for r in reports
                                               if r['accepted_output']),
        'unscored_numeric_claims': sum(len(r['unscored_numeric_claims']) for r in reports),
    }
    return {'metrics': metric, 'cases': reports,
            'unexpected_output_ids': sorted(set(outputs) - {item['id'] for item in cases}),
            'metric_limits': 'Subset labels score only declared facts. Unscored numeric claims are reported separately. '
                             'Field coverage alone is not correctness. Failed candidate output remains failed even when its values match. '
                             'This is text extraction evaluation; it is not an ASR or field-accuracy estimate.'}


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--set', choices=('fresh', 'legacy'), default='fresh', dest='dataset')
    parser.add_argument('--outputs', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    cases, limits = load_cases(args.reference, args.dataset)
    report = {'dataset': args.dataset, 'dataset_limits': limits,
              'evaluator_sha256': file_sha(Path(__file__)),
              'reference_sha256': file_sha(args.reference), 'outputs_sha256': file_sha(args.outputs),
              'inference_executed': False, 'labels_supplied_to_inference': False,
              'evaluation': evaluate(cases, load_outputs(args.outputs))}
    if args.baseline:
        report['baseline_sha256'] = file_sha(args.baseline)
        report['baseline'] = evaluate(cases, load_outputs(args.baseline))
        report['metric_delta'] = {key: value - report['baseline']['metrics'][key]
                                  for key, value in report['evaluation']['metrics'].items()}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    print(json.dumps(report['evaluation']['metrics'], ensure_ascii=False))


if __name__ == '__main__':
    main()
