"""Offline extraction through the already-installed loopback Ollama service.

No model pulling, remote URL, proxy, or input truncation is supported here.
"""
import json
import math
import re
import time
import urllib.error
import urllib.request

BASE_URL = 'http://127.0.0.1:11434'
NUM_PREDICT = 2048
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
CLAIM_SCHEMA = {
    'type': 'object',
    'properties': {
        'claims': {'type': 'array', 'items': {
            'type': 'object',
            'properties': {'field': {'type': 'string'},
                           'value': {'type': ['string', 'number', 'boolean']},
                           'quote': {'type': 'string'}},
            'required': ['field', 'value', 'quote'], 'additionalProperties': False}},
        'uncertainties': {'type': 'array', 'items': {'type': 'string'}},
    },
    'required': ['claims', 'uncertainties'], 'additionalProperties': False,
}


class OllamaExtractionError(ValueError):
    """Protocol failures may retain the returned raw candidate for audit."""
    def __init__(self, message, raw=None, metadata=None):
        super().__init__(message)
        self.raw = raw
        self.metadata = metadata or {}


def _strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise OllamaExtractionError('Ollama 응답에 중복 JSON 키가 있습니다: ' + key)
            result[key] = value
        return result
    def constant(value):
        raise OllamaExtractionError('Ollama 응답의 비정상 숫자: ' + value)
    result = json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    def check(value):
        if isinstance(value, float) and not math.isfinite(value):
            raise OllamaExtractionError('Ollama 응답의 비정상 숫자')
        if isinstance(value, dict):
            for item in value.values():
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)
    check(result)
    return result


def _http_json(path, payload=None):
    if path not in ('/api/tags', '/api/version', '/api/chat'):
        raise OllamaExtractionError('허용하지 않은 로컬 Ollama 경로')
    encoded = None if payload is None else json.dumps(
        payload, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    request = urllib.request.Request(BASE_URL + path, data=encoded,
                                     headers={'Content-Type': 'application/json'})
    # The fixed loopback endpoint must not be routed through HTTP_PROXY.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=600 if payload is not None else 15) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise OllamaExtractionError('Ollama 응답 크기 한도 초과')
        result = _strict_json(raw.decode('utf-8'))
    except (urllib.error.URLError, TimeoutError, UnicodeError, json.JSONDecodeError) as error:
        raise OllamaExtractionError('로컬 Ollama 응답 실패: ' + str(error)) from error
    if not isinstance(result, dict):
        raise OllamaExtractionError('Ollama 응답은 JSON 객체여야 합니다.')
    if result.get('error'):
        raise OllamaExtractionError('Ollama 오류: ' + str(result['error']))
    return result


def _digest(value):
    if not isinstance(value, str):
        raise OllamaExtractionError('Ollama 모델 digest가 필요합니다.')
    value = value.removeprefix('sha256:').lower()
    if not re.fullmatch('[0-9a-f]{64}', value):
        raise OllamaExtractionError('Ollama 모델 digest 형식 오류')
    return value


def installed_model(spec):
    """Require the exact installed tag and pinned digest, without a pull request."""
    for key in ('url', 'base_url', 'host', 'endpoint'):
        if key in spec and spec[key] != BASE_URL:
            raise OllamaExtractionError('Ollama는 고정 로컬 주소만 허용합니다.')
    model = spec.get('model')
    if not isinstance(model, str) or not model.strip() or model != model.strip():
        raise OllamaExtractionError('정확한 로컬 Ollama 모델 태그가 필요합니다.')
    expected = _digest(spec.get('digest'))
    tags = _http_json('/api/tags').get('models')
    if not isinstance(tags, list):
        raise OllamaExtractionError('Ollama 모델 목록 응답 형식 오류')
    found = [item for item in tags if isinstance(item, dict) and
             model in (item.get('name'), item.get('model'))]
    if len(found) != 1:
        raise OllamaExtractionError('설치된 Ollama 모델 태그를 확인하세요: ' + model)
    actual = _digest(found[0].get('digest'))
    if actual != expected:
        raise OllamaExtractionError('설치된 Ollama 모델 digest가 설정과 다릅니다.')
    return {'model': model, 'digest': actual, 'details': found[0].get('details', {})}


def extract(spec, messages):
    """Return (raw assistant content, provenance); never silently truncate input."""
    if not isinstance(spec, dict):
        raise OllamaExtractionError('Ollama 설정은 객체여야 합니다.')
    num_ctx = spec.get('num_ctx', 16384)
    if type(num_ctx) is not int or num_ctx not in (16384, 32768):
        raise OllamaExtractionError('Ollama 문맥 크기는 16384 또는 32768이어야 합니다.')
    if not isinstance(messages, list) or not messages:
        raise OllamaExtractionError('전체 추출 대화가 필요합니다.')
    for message in messages:
        if (not isinstance(message, dict) or set(message) != {'role', 'content'} or
                message['role'] not in ('system', 'user', 'assistant') or
                not isinstance(message['content'], str)):
            raise OllamaExtractionError('지원하지 않는 Ollama 대화 형식')
    # Counting UTF-8 bytes rather than guessing Korean tokens is conservative.
    # Include schema text and framing allowance; the full transcript is retained.
    input_bytes = len(json.dumps({'messages': messages, 'format': CLAIM_SCHEMA},
                                ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    estimated_token_upper_bound = input_bytes + 512
    if estimated_token_upper_bound > num_ctx - NUM_PREDICT:
        raise OllamaExtractionError('전체 입력이 보수적인 문맥 한도를 넘습니다. 자르지 않고 중단합니다.')
    installed = installed_model(spec)
    version = _http_json('/api/version').get('version')
    if not isinstance(version, str) or not version.strip():
        raise OllamaExtractionError('Ollama API 버전을 확인할 수 없습니다.')
    request = {'model': installed['model'], 'messages': messages, 'format': CLAIM_SCHEMA,
               'stream': False, 'think': False, 'keep_alive': '5m',
               'options': {'temperature': 0, 'seed': 0, 'num_ctx': num_ctx,
                           'num_predict': NUM_PREDICT}}
    started_ms = int(time.time() * 1000)
    response = _http_json('/api/chat', request)
    message = response.get('message')
    raw = message.get('content') if isinstance(message, dict) else None
    metadata = {'engine': 'ollama', 'api_url': BASE_URL, 'api_version': version,
                'model': installed['model'], 'digest': installed['digest'],
                'model_details': installed['details'], 'started_ms': started_ms,
                'finished_ms': int(time.time() * 1000),
                'done_reason': response.get('done_reason'),
                'prompt_eval_count': response.get('prompt_eval_count'),
                'eval_count': response.get('eval_count'),
                'total_duration': response.get('total_duration'),
                'load_duration': response.get('load_duration'),
                'prompt_eval_duration': response.get('prompt_eval_duration'),
                'eval_duration': response.get('eval_duration'),
                'input_utf8_bytes': input_bytes,
                'input_token_upper_bound': estimated_token_upper_bound,
                'options': request['options'], 'think': False,
                'input_truncated': False}
    if response.get('done') is not True or response.get('done_reason') != 'stop':
        raise OllamaExtractionError('Ollama 추출이 정상 완료되지 않았습니다: ' +
                                    str(response.get('done_reason')), raw=raw, metadata=metadata)
    if (response.get('model') != installed['model'] or not isinstance(message, dict) or
            message.get('role') != 'assistant' or message.get('tool_calls') or
            not isinstance(raw, str) or not raw.strip()):
        raise OllamaExtractionError('지원하지 않거나 비어 있는 Ollama 응답', raw=raw, metadata=metadata)
    for key in ('prompt_eval_count', 'eval_count'):
        if type(response.get(key)) is not int or response[key] < 0:
            raise OllamaExtractionError('Ollama 토큰 수 응답 오류: ' + key, raw=raw, metadata=metadata)
    if response['prompt_eval_count'] + response['eval_count'] > num_ctx:
        raise OllamaExtractionError('Ollama 문맥 한도 초과 응답', raw=raw, metadata=metadata)
    # Tag changes during execution must not relabel a model result silently.
    try:
        after = installed_model(spec)
    except OllamaExtractionError as error:
        raise OllamaExtractionError('처리 후 모델 확인 실패: ' + str(error),
                                    raw=raw, metadata=metadata) from error
    if after['digest'] != installed['digest']:
        raise OllamaExtractionError('처리 도중 Ollama 모델이 변경됐습니다.', raw=raw, metadata=metadata)
    return raw, metadata
