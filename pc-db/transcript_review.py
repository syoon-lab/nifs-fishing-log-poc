"""Validate an explicitly reviewed transcript without changing its words or provenance."""
import hashlib
import re

MAX_TEXT_BYTES = 100_000
REVIEW_FIELDS = {'version', 'parent_run_id', 'parent_result_sha256', 'selection',
                 'text', 'text_sha256', 'reviewer', 'reason', 'reviewed_at_ms'}


def text_sha256(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def validate_review(review):
    """Return the original object after strict checks; never normalize or repair text."""
    if not isinstance(review, dict) or set(review) != REVIEW_FIELDS:
        raise ValueError('검토 전사 정보의 항목이 올바르지 않습니다.')
    if type(review['version']) is not int or review['version'] != 1:
        raise ValueError('지원하지 않는 검토 전사 버전입니다.')
    if (not isinstance(review['parent_run_id'], str) or not review['parent_run_id'].strip()
            or len(review['parent_run_id']) > 120):
        raise ValueError('검토한 원래 처리 번호가 필요합니다.')
    for key in ('parent_result_sha256', 'text_sha256'):
        if not isinstance(review[key], str) or not re.fullmatch(r'[0-9a-f]{64}', review[key]):
            raise ValueError('검토 전사의 내용 지문이 올바르지 않습니다.')
    if review['selection'] not in ('base', 'dialect', 'corrected'):
        raise ValueError('기본·방언·직접 수정 중 전사 출처를 선택하세요.')
    text = review['text']
    if not isinstance(text, str) or not any(char.isalnum() for char in text):
        raise ValueError('내용이 있는 검토 전사가 필요합니다.')
    try:
        encoded = text.encode('utf-8')
    except UnicodeError as error:
        raise ValueError('검토 전사에 저장할 수 없는 문자가 있습니다.') from error
    if len(encoded) > MAX_TEXT_BYTES:
        raise ValueError('검토 전사는 UTF-8 100,000바이트 이하여야 합니다.')
    if hashlib.sha256(encoded).hexdigest() != review['text_sha256']:
        raise ValueError('검토 전사와 저장한 내용 지문이 다릅니다.')
    for key, label, limit in (('reviewer', '검토자', 120), ('reason', '검토 사유', 2000)):
        value = review[key]
        if not isinstance(value, str) or not value.strip() or len(value) > limit:
            raise ValueError(f'{label}를 1~{limit}자로 입력하세요.')
    if type(review['reviewed_at_ms']) is not int or review['reviewed_at_ms'] <= 0:
        raise ValueError('검토 시각이 올바르지 않습니다.')
    return review


def reviewed_text(result):
    """Use only the explicitly reviewed text and verify retained-source selection."""
    if not isinstance(result, dict) or result.get('text_source') != 'reviewed':
        raise ValueError('검토 전사 출처가 올바르지 않습니다.')
    review = validate_review(result.get('transcript_review'))
    if result.get('reviewed_text') != review['text']:
        raise ValueError('추출에 사용한 문장과 검토 전사가 다릅니다.')
    if review['selection'] in ('base', 'dialect'):
        field = 'asr_text' if review['selection'] == 'base' else 'dialect_text'
        if result.get(field) != review['text']:
            raise ValueError('선택한 원래 전사와 검토 문장이 다릅니다. 문장을 바꾸려면 직접 수정을 선택하세요.')
        if review['selection'] == 'dialect' and result.get('dialect_error'):
            raise ValueError('실패한 방언 전사를 선택할 수 없습니다.')
    return review['text']
